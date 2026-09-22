package com.magfold.cast.window

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import androidx.window.area.WindowAreaCapability
import androidx.window.area.WindowAreaController
import androidx.window.area.WindowAreaInfo
import androidx.window.area.WindowAreaPresentationSessionCallback
import androidx.window.area.WindowAreaSessionPresenter
import androidx.window.core.ExperimentalWindowApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

/**
 * 「双屏幕模式」：内屏照常放控制台，外屏单独渲染当前图片/视频。
 *
 * 两个必须记住的点：
 *  1. 只有 OPERATION_PRESENT_ON_AREA 才是"内外屏同时显示"。
 *     OPERATION_TRANSFER_ACTIVITY_TO_AREA 是**后置显示屏模式** —— 把整个界面搬到外屏、
 *     内屏自动关掉，那是后置摄像头自拍取景用的，跟这个需求完全不是一回事。
 *  2. 支持度必须运行时探测，不能假设。UNSUPPORTED = 本机没实现这套 window area 扩展；
 *     UNAVAILABLE = 当前形态不可用（比如手机是合着的）；AVAILABLE 才允许发起会话。
 *
 * 这套 API 自 Jetpack WindowManager 1.2.0-beta03 起对第三方开放，
 * 三星从 One UI 6.0 开始支持（Galaxy Z Fold 系列）。国产折叠屏基本没有部署这个扩展。
 *
 * ## 能力探测**不能**绑在 STARTED 上（这一条踩过，代价是"进后台外屏就黑"）
 *
 * 探测循环曾经用 `repeatOnLifecycle(STARTED)` 包着。后果是一条连锁反应：
 * 应用一进后台 → Activity 停在 STARTED 之下 → 协程被取消 → `runCatching` 把
 * `CancellationException` 当普通失败吞掉 → 能力被**改写**成 UNSUPPORTED →
 * 界面看到"本机不支持双屏"就把内屏顶成全屏舞台、并把播放器抢过去 →
 * 外屏那块 Surface 当场失去画面，也就是"进后台就黑"。
 *
 * 两处都修了，缺一不可：
 *  - 探测循环改用传入的 scope 直接收集（不再随 STARTED 起落），取消也不再被当成失败
 *    （`CancellationException` 原样抛出）。**这个应用退到后台正是它的正常工作状态**，
 *    外屏还开着的时候能力必须继续是 ACTIVE；
 *  - 界面侧（[com.magfold.cast.ui.AppRoot]）判定"该不该自己当舞台"时会再排除
 *    "会话已经在跑"的情形，作为第二道保险。
 *
 * ## 三件容易被忽略的事
 *
 *  - **`windowAreaInfos` 里带 `metrics`**，那就是外屏的像素尺寸。内屏分栏预览按它算长宽比，
 *    所以预览不用猜常数（见 [CoverStatus.coverAspect]）。
 *  - **发起会话是异步的**。`presentContentOnWindowArea` 返回时会话还没起来，回调之前
 *    有一小段空窗。这段空窗必须显式标成 [CoverStatus.requesting]，否则界面会把
 *    "还没开始投" 误读成 "不支持投放"。
 *  - **探测本身也可能没有结论**（扩展静默失效）。所以加了两道超时兜底，
 *    免得界面永远停在"检测中"。
 *
 * ## 会话与容器都可能在后台被系统收走
 *
 * 所以自愈点有三处：会话被结束而内容还在时**指数退避重开**（约 50 秒的窗口，
 * 覆盖"切到桌面待一会儿"）；容器重新可见时**重新塞一份内容**；容器被藏起来时
 * **补一次内容并记日志**。回到前台还有 [refresh] 兜最后一次。
 *
 * ### "切到桌面外屏就黑"要分清是哪一种
 *
 * 外屏画面挂在 Activity 上，应用不可见时它可能以两种方式出问题，**只有其中一种是
 * 我们能修的**，所以日志必须能分辨：
 *
 *  - **我们这边的**：外屏容器 View 被系统 detach → 组合被销毁/挂起。
 *    `AbstractComposeView` 的默认组合策略是 `DisposeOnDetachedFromWindow...`，
 *    也就是说 detach 会**销毁内容**。这个已经修掉了（见 [CoverComposeView]：
 *    改成 `DisposeOnLifecycleDestroyed`，detach 只暂停），并且还有补内容这条路。
 *  - **厂商那边的**：系统主动把展示容器收起来（`onContainerVisibilityChanged(false)`），
 *    因为宿主 Activity 不可见了。这时**画面是系统带着的，应用侧无法阻止** ——
 *    能做的只有"回前台立刻补回来"，日志里会看到一条"容器可见性 -> false"。
 *
 * 两种都会在 `adb logcat -s WindowAreaBridge CoverComposeView` 里留下痕迹，
 * 拿着那几行就能判断到底是哪一种，不用猜。
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@OptIn(ExperimentalWindowApi::class)
class WindowAreaBridge(private val activity: ComponentActivity) : CoverDisplay,
    WindowAreaPresentationSessionCallback {

    private val controller: WindowAreaController = WindowAreaController.getOrCreate()
    private val executor: Executor = ContextCompat.getMainExecutor(activity)

    private var areaInfo: WindowAreaInfo? = null
    private var session: WindowAreaSessionPresenter? = null
    private var watcher: Job? = null
    private var host: CoroutineScope? = null

    private val capability = MutableStateFlow(
        WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED,
    )
    private val presentingFlag = MutableStateFlow(false)
    private val requestingFlag = MutableStateFlow(false)

    private var detected = false
    private var coverWidth = 0
    private var coverHeight = 0

    /** 会话被系统收走后的自动重开次数。有上限，免得和厂商实现互相踢皮球。 */
    private var presentRetries = 0

    /** 容器是否被隐藏过。重新可见时要补内容（见 [onContainerVisibilityChanged]）。 */
    private var containerHidden = false

    /**
     * "外屏那份内容可能已经不在了"。
     *
     * 由两件事置真：容器被藏起来、或我们那份 View 被 detach。用途是让 [refresh]
     * 只在**真有嫌疑**时才换内容 —— 换内容 = 换一份新 View，在屏幕上会闪一下。
     * 从相册选完素材回到前台也会走 onResume，那种情况下画面好端端的，
     * 不该为了一次保险让它闪。
     */
    private var contentSuspect = false

    /**
     * 本次展出周期里成功把内容放上过外屏没有。
     *
     * 界面侧拿它当"这台机器确实能投"的记忆（见 [CoverStatus.engaged]）：
     * 应用退到后台时能力探测可能一时读不出 AVAILABLE，界面若照此判定"不支持"，
     * 就会用内屏去抢播放器、把外屏那块 Surface 弄黑。投上过就是投上过。
     * [hide]（收起外屏）时清零 —— 那是新的一轮。
     */
    private var everPresented = false

    /** 当前挂在会话里的那份内容。换内容时要显式销毁旧的（detach 已经不管销毁了）。 */
    private var currentHost: CoverComposeView? = null

    /** 容器不可见期间的补内容次数与计时窗口，防止和系统实现来回踢皮球。 */
    private var repairCount = 0
    private var repairWindowStart = 0L

    private val _status = MutableStateFlow(CoverStatus())
    override val status: StateFlow<CoverStatus> = _status

    private var content: (@Composable () -> Unit)? = null

    fun observe(scope: CoroutineScope) {
        if (watcher?.isActive == true) return
        host = scope
        watcher = scope.launch {
            // 兜底一：扩展没部署时 windowAreaInfos 可能既不报错也不发值。
            // 到点还没结论就按"不支持"落定，让界面能走「内屏铺满 / 分栏预览」。
            launch {
                delay(DETECT_TIMEOUT_MS)
                if (!detected) {
                    Log.w(TAG, "窗口区域探测超时（${DETECT_TIMEOUT_MS}ms），按不支持处理")
                    detected = true
                    publish()
                }
            }

            // 刻意直接收集，**不套 repeatOnLifecycle(STARTED)**：见类注释里的连锁反应。
            try {
                controller.windowAreaInfos.collect { infos ->
                    val info = infos.firstOrNull {
                        it.type == WindowAreaInfo.Type.TYPE_REAR_FACING
                    }
                    if (info != null) {
                        areaInfo = info
                        // 外屏的真实尺寸 —— 分栏预览按它画，不靠猜
                        val bounds = info.metrics.bounds
                        coverWidth = bounds.width()
                        coverHeight = bounds.height()
                    }
                    val next = info?.getCapability(OPERATION_PRESENT_ON_AREA)?.status
                        ?: WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED
                    val changed = next != capability.value
                    capability.value = next
                    detected = true
                    if (changed) Log.d(TAG, "外屏能力 -> $next")
                    publish()
                    // 能力就绪时若已经有内容待展示（比如先选了素材、后展开机身），自动开屏
                    if (changed) autoPresent()
                }
            } catch (cancelled: CancellationException) {
                // 取消不是失败：别把它翻译成"不支持外屏"
                throw cancelled
            } catch (error: Throwable) {
                // 兜底二：探测流程本身炸了（扩展版本对不上等），同样落在"不支持"
                Log.w(TAG, "窗口区域能力探测失败，退化为仅内屏", error)
                capability.value = WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED
                detected = true
                publish()
            }
        }
    }

    override fun show(content: @Composable () -> Unit) {
        this.content = content
        presentRetries = 0
        val active = session
        if (active == null) {
            // 首次挂内容：能力允许就自己开，不需要界面再点一下
            autoPresent()
        } else {
            // 会话已经开着就热替换内容，不用重开会话
            setHostContent(active)
        }
    }

    override fun hide() {
        // 先清内容再关会话：否则会话结束会让能力回到 AVAILABLE，
        // 而 autoPresent() 看到内容还在就又把外屏开起来了。
        content = null
        presentRetries = 0
        everPresented = false
        containerHidden = false
        contentSuspect = false
        clearRequest()
        session?.let { active ->
            runCatching { active.close() }.onFailure { Log.w(TAG, "关闭外屏会话失败", it) }
        }
        clearHost()
    }

    /**
     * 应用回到前台时"再确认一遍"外屏：会话没了就重开，会话在就重塞一份内容。
     *
     * 为什么必须有这么一下：外屏画面挂在 Activity 上，应用不可见时系统完全可能
     * 把会话或容器收走，而那时我们既看不到也没法补。回到前台的这一刻是**唯一
     * 确定能补回来的时机**（详见 [CoverDisplay.refresh]）。
     */
    override fun refresh() {
        if (content == null) return
        // 新一轮尝试，把重开次数与补内容额度都放回满值
        presentRetries = 0
        repairCount = 0
        val active = session
        when {
            active == null -> {
                Log.d(TAG, "回前台复查：会话不在了，重新开外屏")
                autoPresent()
            }
            // 只有"内容可能有嫌疑"时才重塞。没嫌疑就什么都不做 ——
            // 换内容在屏幕上会闪一下，从相册回来的那种 onResume 不值得闪。
            contentSuspect -> {
                Log.d(TAG, "回前台复查：会话在但内容可疑，重塞一份")
                refillContent()
            }
            else -> Log.d(TAG, "回前台复查：会话与内容都正常，不动")
        }
    }

    override fun toggle() {
        val active = session
        if (active != null) {
            runCatching { active.close() }.onFailure { Log.w(TAG, "关闭外屏会话失败", it) }
            return
        }
        val token = areaInfo?.token ?: return
        if (!canPresent) return
        // 从这里开始到 onSessionStarted 之间是"请求中"。
        // 界面必须能看出这段空窗不是失败 —— 这正是内屏不再闪全屏舞台的关键。
        requestingFlag.value = true
        publish()
        armRequestWatchdog()
        runCatching {
            controller.presentContentOnWindowArea(
                token = token,
                activity = activity,
                executor = executor,
                windowAreaPresentationSessionCallback = this,
            )
        }.onFailure {
            Log.w(TAG, "发起双屏幕会话失败", it)
            clearRequest()
        }
    }

    /** 有内容待展示、能力允许、且当前没开着 —— 三个条件齐了才开。 */
    private fun autoPresent() {
        if (content == null || session != null || requestingFlag.value || !canPresent) return
        toggle()
    }

    override fun onSessionStarted(session: WindowAreaSessionPresenter) {
        this.session = session
        presentingFlag.value = true
        presentRetries = 0
        // 从这一刻起，"这台机器能投外屏"就是既成事实，界面侧靠它扛住后台期的误判
        everPresented = true
        clearRequest()
        setHostContent(session)
        Log.d(TAG, "外屏会话已开始")
        publish()
    }

    override fun onSessionEnded(t: Throwable?) {
        session = null
        presentingFlag.value = false
        clearRequest()
        if (t != null) Log.w(TAG, "双屏幕会话异常结束", t) else Log.d(TAG, "外屏会话已结束")
        // 会话没了，容器也跟着没了：把那份内容显式销毁（detach 只暂停不销毁，会挂住）
        clearHost()
        publish()

        // 内容还在（用户没收起外屏）的话自己再开一次：挂在墙上的展窗不该因为
        // 系统回收了一次会话就一直黑着。重试用**指数退避** —— 会话被收走往往
        // 就发生在应用不可见的那几秒里，早先版本"连试 3 次、每次隔 800ms"
        // 全落在那段窗口里，等于没试。次数有上限，免得和厂商实现互相踢皮球。
        if (content != null && presentRetries < MAX_PRESENT_RETRIES) {
            presentRetries++
            val wait = retryDelayMs(presentRetries)
            Log.d(TAG, "会话已结束但内容还在，第 $presentRetries 次尝试重开外屏（等 ${wait}ms）")
            host?.launch {
                delay(wait)
                autoPresent()
            }
        } else if (content != null) {
            Log.w(TAG, "重开外屏尝试次数已用尽，等回前台再复查（refresh）")
        }
    }

    override fun onContainerVisibilityChanged(isVisible: Boolean) {
        Log.d(TAG, "外屏容器可见性 -> $isVisible")
        if (!isVisible) {
            // 系统把容器藏起来了。**两个来源要分清**：
            //  ① 应用退到后台，厂商实现顺手把展示容器收起来 —— 这时外屏会黑，
            //     而且应用侧无法阻止（画面是系统带着的），只能等回前台补回来；
            //  ② 我们自己那份内容被 detach 掉了（组合被挂起/销毁 → 空白）。
            // 分不清就只能都试一次：补一份新内容。补得回来是 ②，补不回来是 ①。
            containerHidden = true
            contentSuspect = true
            scheduleContainerRepair()
            return
        }
        if (containerHidden) {
            containerHidden = false
            // 只有"内容可疑"才补：detach 现在只等于暂停（见 [CoverComposeView]），
            // 同一份 View re-attach 回来就能继续画 —— 那种情况下补一份新的，
            // 只会在外屏上白闪一下。
            if (contentSuspect) {
                refillContent()
            } else {
                Log.d(TAG, "外屏容器回来了，内容未受损，不重塞")
            }
        }
    }

    /**
     * 容器不可见后补一次内容（有额度，免得跟系统实现来回踢皮球）。
     *
     * 只在**容器不可见**时补：可见时 setContentView 会换一份新 View，
     * 在屏幕上就是闪一下 —— 那次闪烁没有必要。
     */
    private fun scheduleContainerRepair() {
        if (content == null) return
        val now = SystemClock.elapsedRealtime()
        if (now - repairWindowStart > REPAIR_WINDOW_MS) {
            repairWindowStart = now
            repairCount = 0
        }
        if (repairCount >= MAX_CONTAINER_REPAIRS) {
            Log.d(TAG, "容器补内容额度已用尽，本窗口内不再补（等回前台复查）")
            return
        }
        repairCount++
        host?.launch {
            delay(REPAIR_DELAY_MS)
            // 期间容器又回来了就不用补了（那条路径已经补过）
            if (content == null || !containerHidden || session == null) return@launch
            Log.d(TAG, "容器不可见，补内容第 $repairCount 次（额度 $MAX_CONTAINER_REPAIRS/$REPAIR_WINDOW_MS ms）")
            refillContent()
        }
    }

    /** 重新塞一份外屏内容。会话没了就顺带重开。 */
    private fun refillContent() {
        if (content == null) return
        val active = session
        if (active == null) {
            autoPresent()
            return
        }
        setHostContent(active)
    }

    /**
     * 换一份外屏内容：新建容器 View、交给会话，**并把上一份显式销毁**。
     *
     * 销毁这一步是必须的：detach 只把组合挂起（见 [CoverComposeView]），
     * 不销毁的话，每次换内容都会留下一个挂着帧时钟的组合。
     */
    private fun setHostContent(session: WindowAreaSessionPresenter) {
        val previous = currentHost
        val next = hostView(session.context)
        currentHost = next
        val ok = runCatching { session.setContentView(next) }
            .onFailure { Log.w(TAG, "设置外屏内容失败", it) }
            .isSuccess
        if (ok) contentSuspect = false
        if (previous != null && previous !== next) {
            runCatching { previous.disposeNow() }
        }
    }

    /** 销毁当前那份内容（会话结束、收起外屏时调用）。 */
    private fun clearHost() {
        currentHost?.let { runCatching { it.disposeNow() } }
        currentHost = null
    }

    /** 第 n 次重开的等待时间：指数退避，封顶 [RETRY_MAX_MS]。 */
    private fun retryDelayMs(attempt: Int): Long {
        var wait = RETRY_BASE_MS
        repeat(attempt - 1) {
            wait = (wait * 2).coerceAtMost(RETRY_MAX_MS)
        }
        return wait
    }

    private val canPresent: Boolean
        get() = with(capability.value) {
            this == WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE ||
                this == WindowAreaCapability.Status.WINDOW_AREA_STATUS_ACTIVE
        }

    /**
     * 发起请求后迟迟等不到回调（比如厂商实现只吞了调用）。
     * 清掉"请求中"，界面回到控制台 —— 而不是让"正在投放"永远转下去。
     */
    private fun armRequestWatchdog() {
        host?.launch {
            delay(REQUEST_TIMEOUT_MS)
            if (requestingFlag.value && session == null) {
                Log.w(TAG, "投放请求超时（${REQUEST_TIMEOUT_MS}ms），退回控制台")
                clearRequest()
            }
        }
    }

    private fun clearRequest() {
        if (!requestingFlag.value) return
        requestingFlag.value = false
        publish()
    }

    private fun publish() {
        val next = CoverStatus(
            detected = detected,
            available = canPresent,
            presenting = presentingFlag.value,
            requesting = requestingFlag.value,
            engaged = everPresented,
            coverWidth = coverWidth,
            coverHeight = coverHeight,
            text = statusLabel(),
        )
        // 只在真的变了的时候写流 + 记日志：这个流内外屏都在收，没必要空转。
        // 而且这条日志是"外屏为什么黑"的现场记录（能力掉了？会话没了？），
        // 比事后猜有用得多 —— adb logcat -s WindowAreaBridge
        if (next != _status.value) {
            val before = _status.value
            Log.d(
                TAG,
                "外屏状态 ${before.text} -> ${next.text} " +
                    "(可用 ${before.available}->${next.available} " +
                    "会话 ${before.presenting}->${next.presenting} " +
                    "曾投上 ${before.engaged}->${next.engaged})",
            )
        }
        _status.value = next
    }

    private fun statusLabel(): String = when {
        !detected -> "正在检测外屏…"
        requestingFlag.value -> "正在投放到外屏…"
        capability.value == WindowAreaCapability.Status.WINDOW_AREA_STATUS_ACTIVE -> "外屏投放中"
        canPresent -> "外屏待命"
        capability.value == WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNAVAILABLE ->
            "外屏暂不可用 · 展开机身"
        else -> "本机不支持外屏投放 · 可用内屏铺满或分栏预览"
    }

    private fun hostView(context: Context): CoverComposeView {
        val current = content
        return CoverComposeView(
            context = context,
            content = { current?.invoke() },
            onDetached = {
                // detach 本身不再销毁内容（见 CoverComposeView），但"被 detach 过"
                // 仍然值得记一笔：回前台时据此判断要不要重塞一份。
                contentSuspect = true
                Log.d(TAG, "外屏内容 View 被 detach（组合只挂起，等 re-attach 或补内容）")
            },
        )
    }

    companion object {
        private const val TAG = "WindowAreaBridge"

        /** 探测多久没结论就按"不支持"落定。 */
        private const val DETECT_TIMEOUT_MS = 2500L

        /** 发起会话后多久等不到回调就当失败。 */
        private const val REQUEST_TIMEOUT_MS = 6000L

        /**
         * 会话被系统收走后最多自己重开几次。
         *
         * 取 8 是大有讲究的：配合指数退避，这条链大约能覆盖 50 秒 ——
         * 「切到桌面待一会儿再回来」这段典型的后台时长正好落在里面。
         * 早先"3 次 × 800ms"只覆盖 2.4 秒，等于没试。
         */
        private const val MAX_PRESENT_RETRIES = 8

        /** 重开重试的首次等待与最长等待（指数退避）。 */
        private const val RETRY_BASE_MS = 400L
        private const val RETRY_MAX_MS = 15000L

        /** 容器不可见时的补内容：额度与窗口。 */
        private const val MAX_CONTAINER_REPAIRS = 3
        private const val REPAIR_WINDOW_MS = 60_000L
        private const val REPAIR_DELAY_MS = 400L

        /** 唯一表示"内外屏同时显示"的操作。 */
        private val OPERATION_PRESENT_ON_AREA: WindowAreaCapability.Operation =
            WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA
    }
}
