package com.magfold.cast.ui

import androidx.activity.compose.BackHandler
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.magfold.cast.serve.CastService
import com.magfold.cast.show.ShowState
import com.magfold.cast.window.CoverDisplay
import com.magfold.cast.window.CoverStatus
import com.magfold.cast.window.FALLBACK_COVER_ASPECT
import kotlinx.coroutines.flow.StateFlow

/** 形态判定日志的标签。外屏黑屏时靠它一眼定位，见 [AppRoot] 里那行日志的注释。 */
private const val TAG = "AppRoot"

/**
 * 根节点：决定「这一屏该显示什么」。
 *
 * 四种形态：
 *  1. 折起 → Activity 被系统挪到外屏，本屏自己就是外屏，直接铺舞台；
 *  2. 分栏预览开着 → 一侧按外屏长宽比纯看，另一侧是控制台；
 *  3. 展开 + 本机确实不支持双屏 + 投屏开 → 内屏顶上全屏舞台（"铺满内屏"）；
 *  4. 其余（含展开 + 支持双屏 + 投屏开）→ 控制台，外屏由窗口会话单独渲染。
 *
 * **第 3 条与第 4 条的判据是"能力"，不是"此刻在不在投"。**
 * 这不是风格问题：`presentContentOnWindowArea` 是异步的，从发起到
 * `onSessionStarted` 之间有一段空窗，那段时间 presenting 还是 false。
 * 早先按 presenting 判定，于是点「投到外屏」会先进第 3 条、把素材放大铺满内屏，
 * 等会话起来再切回控制台 —— 那就是"点投屏先放大一下"的来源。
 * 现在只在「探测已出结论 + 本机没有这个能力」时才铺满，空窗期界面留在控制台，
 * 并把状态写成「正在投放到外屏…」。
 *
 * 舞台内容一律包成 CastStageLive 自己 collect 流（易变值不能进 LaunchedEffect 的 key，
 * 否则每换一张素材都会重建外屏容器）。
 */
@Composable
fun AppRoot(
    show: ShowState,
    cover: CoverDisplay,
    overlayGranted: StateFlow<Boolean>,
    onPickMedia: () -> Unit,
    onGrantOverlay: () -> Unit,
) {
    val context = LocalContext.current
    val casting by show.casting.collectAsState()
    val previewing by show.previewing.collectAsState()
    val previewSide by show.previewSide.collectAsState()
    val keepAlive by show.keepAlive.collectAsState()
    val screenOn by show.screenOn.collectAsState()
    val coverStatus by cover.status.collectAsState()
    var settingsOpen by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 折起态判定与另外两个工程同一约定：窗口宽度 < 600dp 即外屏
        val onCoverScreen = maxWidth < 600.dp
        // BoxWithConstraintsScope 的这两个值必须先落到本地变量：一旦进了 Row/Column 的
        // lambda，隐式接收者就变成 RowScope/ColumnScope，再写 maxHeight 会编译不过
        // （"'val maxHeight: Dp' cannot be called in this context with an implicit receiver"）。
        val windowWidth = maxWidth
        val windowHeight = maxHeight
        val presentable = coverStatus.available
        // 「会话真的在跑」与「能力可用」是两件事，别混用：前者含"请求中"那段空窗
        val sessionUp = coverStatus.presenting || coverStatus.requesting
        // 「本体自己当舞台」只认能力，且要探测有结论（未知 ≠ 不支持）。
        // 两道保险，都是同一件事：**内屏绝不能在外屏正显示着的时候抢播放器**（一个
        // ExoPlayer 只能挂一块 Surface，被抢走的那块当场变黑）。
        //  1. 会话在跑、或投放请求正在路上时不让（[CoverStatus.presenting] /
        //     [CoverStatus.requesting]）—— 后者是"折叠展开"这类姿态切换的过渡期，
        //     能力值可能一时读不出来，不能拿它当"不支持"；
        //  2. 这次展出周期里**成功投上过外屏**就永远不让（[CoverStatus.engaged]）——
        //     这一条是给"进后台"兜的底：应用不可见时能力探测可能一时读不出
        //     AVAILABLE/ACTIVE（甚至会话被系统收走），若只看此刻的能力，内屏会以为
        //     "这台机器不支持"，于是把播放器抢过去 —— 外屏就黑了。
        //     而这次展出明明已经投上过，说明机器是支持的，不该翻脸。
        val selfStage = casting && coverStatus.detected && !presentable &&
            !coverStatus.presenting && !coverStatus.requesting && !coverStatus.engaged
        // 「铺满内屏」和「分栏预览」是同一个诉求的两种画法，后者优先
        val split = previewing && !onCoverScreen && !selfStage
        // 播放器同一时刻只能挂一块屏。归属按**会话**判定，不按能力：
        // 能力可用只说"这台机器能投"，会话在跑才说明"外屏此刻正在显示"。
        // 折起时本体就是外屏，那一种例外。
        val coverOwnsPlayer = casting && (onCoverScreen || sessionUp || coverStatus.engaged)

        // 形态判定是这套"内外屏抢一块 Surface"逻辑里最容易出事的一环，
        // 而外屏黑屏在界面上看不出原因（它什么文字都没有）。所以把决策结果打成一行日志：
        //   adb logcat -s AppRoot WindowAreaBridge CoverComposeView
        // 一行就能看出到底是"判成不支持了"还是"外屏容器被收走了"。
        LaunchedEffect(casting, onCoverScreen, selfStage, split, coverOwnsPlayer, coverStatus) {
            Log.d(
                TAG,
                "形态 casting=$casting 本体外屏=$onCoverScreen 本体当舞台=$selfStage 分栏=$split " +
                    "播放器归外屏=$coverOwnsPlayer | 探测有结论=${coverStatus.detected} " +
                    "可用=${coverStatus.available} 会话中=${coverStatus.presenting} " +
                    "请求中=${coverStatus.requesting} 曾投上=${coverStatus.engaged}",
            )
        }

        LaunchedEffect(casting, onCoverScreen) {
            if (casting && !onCoverScreen) {
                // 外屏自己是主舞台
                cover.show { CastStageLive(show, attachPlayer = true) }
            } else {
                cover.hide()
            }
        }

        // 保活：让进程在应用不可见时活着，外屏会话和播放器才不会被回收。
        // 只在「正在展出 + 后台保持」时开 —— 收起外屏还挂着通知栏就成骚扰了。
        //
        // 常亮（screenOn）也挂在这个 key 上，但**界面并不持锁**：这里只是「重投一次服务」，
        // 服务的 onStartCommand 会自己读落盘开关去对齐屏幕锁（见 CastService.syncWake）。
        // 走同一条路径，从后台被系统重启的服务、开机自启拉起的服务，
        // 对齐逻辑就只有一份，不会出现"界面开着常亮但服务那边不知道"。
        LaunchedEffect(casting, keepAlive, screenOn) {
            if (casting && keepAlive) {
                CastService.start(context)
            } else {
                CastService.stop(context)
            }
        }

        // 换了形态就别让设置面板飘着
        LaunchedEffect(onCoverScreen, selfStage) {
            if (onCoverScreen || selfStage) settingsOpen = false
        }

        // 返回键的优先序：先关设置，再退预览，最后才停展出
        BackHandler(enabled = settingsOpen || previewing || casting) {
            when {
                settingsOpen -> settingsOpen = false
                previewing -> show.setPreviewing(false)
                else -> show.setCasting(false)
            }
        }

        val console: @Composable (Modifier) -> Unit = { target ->
            ConsoleLive(
                show = show,
                cover = coverStatus,
                overlayGranted = overlayGranted,
                presentable = presentable,
                settingsOpen = settingsOpen,
                onSettingsChange = { settingsOpen = it },
                onPickMedia = onPickMedia,
                onGrantOverlay = onGrantOverlay,
                // 本机没法真投外屏时，「铺满内屏」和「分栏预览」不能同时开：
                // 点哪个就把另一个让掉，免得两个开关都亮着却只有一边有画面。
                onToggleCasting = {
                    val next = !casting
                    if (next && !presentable && previewing) show.setPreviewing(false)
                    show.setCasting(next)
                },
                onTogglePreviewing = {
                    val next = !previewing
                    if (next && !presentable && casting) show.setCasting(false)
                    show.setPreviewing(next)
                },
                modifier = target,
            )
        }

        when {
            onCoverScreen -> CastStageLive(show = show, attachPlayer = true)

            split -> Row(modifier = Modifier.fillMaxSize().background(Palette.Paper)) {
                val aspect = coverStatus.coverAspect ?: FALLBACK_COVER_ASPECT
                val paneWidth = previewPaneWidth(windowHeight, windowWidth, aspect)
                if (previewSide == 1) {
                    console(Modifier.weight(1f))
                    PaneDivider()
                    PreviewLive(show, coverStatus, attachPlayer = !coverOwnsPlayer, paneWidth = paneWidth)
                } else {
                    PreviewLive(show, coverStatus, attachPlayer = !coverOwnsPlayer, paneWidth = paneWidth)
                    PaneDivider()
                    console(Modifier.weight(1f))
                }
            }

            selfStage -> CastStageLive(show = show, attachPlayer = true)

            else -> console(Modifier.fillMaxSize())
        }
    }
}

/** 分栏之间那条竖线，跟页面的分隔线同一种笔触。 */
@Composable
private fun PaneDivider() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.Hairline))
}

/** 舞台的实时版：自己 collect，供外屏容器与内屏共用。 */
@Composable
private fun CastStageLive(show: ShowState, attachPlayer: Boolean) {
    val current by show.current.collectAsState()
    val shot by show.shot.collectAsState()
    val fill by show.fill.collectAsState()

    CastStage(
        entry = current,
        shot = shot?.bitmap,
        fill = fill,
        player = show.player,
        attachPlayer = attachPlayer,
    )
}

/** 预览栏的实时版。 */
@Composable
private fun PreviewLive(
    show: ShowState,
    cover: CoverStatus,
    attachPlayer: Boolean,
    paneWidth: Dp,
) {
    val current by show.current.collectAsState()
    val shot by show.shot.collectAsState()
    val fill by show.fill.collectAsState()
    val side by show.previewSide.collectAsState()

    PreviewPane(
        entry = current,
        shot = shot?.bitmap,
        fill = fill,
        player = show.player,
        attachPlayer = attachPlayer,
        cover = cover,
        side = side,
        paneWidth = paneWidth,
        onPrevious = show::previous,
        onNext = show::next,
    )
}

/** 控制台的实时版。 */
@Composable
private fun ConsoleLive(
    show: ShowState,
    cover: CoverStatus,
    overlayGranted: StateFlow<Boolean>,
    presentable: Boolean,
    settingsOpen: Boolean,
    onSettingsChange: (Boolean) -> Unit,
    onPickMedia: () -> Unit,
    onGrantOverlay: () -> Unit,
    onToggleCasting: () -> Unit,
    onTogglePreviewing: () -> Unit,
    modifier: Modifier,
) {
    val entries by show.entries.collectAsState()
    val index by show.index.collectAsState()
    val fill by show.fill.collectAsState()
    val muted by show.muted.collectAsState()
    val playing by show.playing.collectAsState()
    val casting by show.casting.collectAsState()
    val previewing by show.previewing.collectAsState()
    val previewSide by show.previewSide.collectAsState()
    val intervalSec by show.intervalSec.collectAsState()
    val videoFullPlay by show.videoFullPlay.collectAsState()
    val keepAlive by show.keepAlive.collectAsState()
    val screenOn by show.screenOn.collectAsState()
    val bootStart by show.bootStart.collectAsState()
    val granted by overlayGranted.collectAsState()

    ConsoleScreen(
        entries = entries,
        index = index,
        casting = casting,
        previewing = previewing,
        presentable = presentable,
        intervalSec = intervalSec,
        videoFullPlay = videoFullPlay,
        fill = fill,
        muted = muted,
        playing = playing,
        previewSide = previewSide,
        keepAlive = keepAlive,
        screenOn = screenOn,
        bootStart = bootStart,
        overlayGranted = granted,
        cover = cover,
        settingsOpen = settingsOpen,
        onPick = onPickMedia,
        onSelect = show::select,
        onRemove = show::removeAt,
        onPrevious = show::previous,
        onNext = show::next,
        onToggleCasting = onToggleCasting,
        onTogglePreviewing = onTogglePreviewing,
        onTogglePlaying = show::togglePlaying,
        onSettingsChange = onSettingsChange,
        onSetInterval = show::setInterval,
        onToggleVideoFullPlay = show::toggleVideoFullPlay,
        onToggleFill = show::toggleFill,
        onToggleMuted = show::toggleMuted,
        onFlipPreviewSide = show::flipPreviewSide,
        onToggleKeepAlive = show::toggleKeepAlive,
        onToggleScreenOn = show::toggleScreenOn,
        onToggleBootStart = show::toggleBootStart,
        onGrantOverlay = onGrantOverlay,
        onClear = show::clearAll,
        modifier = modifier,
    )
}
