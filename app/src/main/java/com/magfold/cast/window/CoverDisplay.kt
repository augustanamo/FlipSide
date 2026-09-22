package com.magfold.cast.window

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 外屏尺寸未知时用的兜底长宽比（竖幅 = 短边/长边）。Fold 系列外屏约 21.6:9 竖过来。 */
const val FALLBACK_COVER_ASPECT = 0.43f

/**
 * 外屏（副屏）状态，供界面显示与判定。
 *
 * 这里有两个字段值得单独说明，它们是「点投屏时内屏先闪一下放大」那个 bug 的解药：
 *
 *  - [detected]：**能力探测是否有结论**。冷启动时能力还是未知，早先的代码把
 *    「unknown」和「unsupported」当成一回事，于是会先铺一屏全屏舞台再收回去。
 *  - [requesting]：**已经发出投放请求、但会话还没起来**。这段空窗期同样不是
 *    「不支持」，界面该显示「正在投放到外屏…」而不是改行做别的事。
 *
 * 另外带上了外屏的真实像素尺寸 —— 内屏分栏预览要按它算长宽比。
 * 尺寸来自 window area 给的 WindowMetrics（见 [WindowAreaBridge]），拿不到时为 0。
 */
data class CoverStatus(
    val detected: Boolean = false,
    val available: Boolean = false,
    val presenting: Boolean = false,
    val requesting: Boolean = false,
    /**
     * **本次展出周期里成功把内容放上过外屏。**
     *
     * 这是个"记忆"字段，存在的理由是界面侧的判据不能只看此刻的能力值：
     * 应用退到后台时，能力探测可能一时读不出 AVAILABLE/ACTIVE（会话也可能被系统收走），
     * 界面若照此判定"本机不支持双屏"，就会把内屏顶成全屏舞台并**抢走播放器** ——
     * 外屏那块 Surface 当场失去画面，就是"一去桌面外屏就黑"。
     * 投上过就是投上过，机器支持这件事不会因为应用不可见而改变。
     *
     * 生命周期：`onSessionStarted` 置真，[CoverDisplay.hide]（收起外屏）置假。
     */
    val engaged: Boolean = false,
    val coverWidth: Int = 0,
    val coverHeight: Int = 0,
    val text: String = "检测中…",
) {
    /**
     * 外屏长宽比（宽/高），**恒小于 1 的竖幅**。
     *
     * 系统有可能按自己的旋转把外屏报成横向，但外屏那块面板的物理形状不会变 ——
     * 展开时它永远是沿机身高度方向的窄条。所以这里统一取短边/长边。
     */
    val coverAspect: Float?
        get() = if (coverWidth > 0 && coverHeight > 0) {
            minOf(coverWidth, coverHeight).toFloat() / maxOf(coverWidth, coverHeight).toFloat()
        } else {
            null
        }

    /** 给界面看的尺寸文案，拿不到就照实说。 */
    val coverLabel: String
        get() = if (coverWidth > 0 && coverHeight > 0) {
            "外屏 " + minOf(coverWidth, coverHeight) + " × " + maxOf(coverWidth, coverHeight)
        } else {
            "外屏尺寸未知"
        }
}

/**
 * 外屏控制器的抽象。
 *
 * 把窗口区域 API 藏在这个接口后面有两个好处：
 *  - Android 14 以下的机型直接给一个空实现，界面代码完全不需要感知 API 级别；
 *  - 双屏幕模式相关的类型只在 [WindowAreaBridge] 一个文件里出现，万一
 *    AndroidX 那边签名有出入，只需要改那一个文件。
 *
 * 用法就三条：[show] 挂上内容（能力允许时**自动开启**，不需要调用方再手动开关），
 * [hide] 撤下内容并关掉会话，[refresh] 让界面在回到前台时"再确认一遍"外屏还亮着。
 */
interface CoverDisplay {
    val status: StateFlow<CoverStatus>

    /** 设置外屏内容并尝试开启。重复调用只做热替换，不会重开会话。 */
    fun show(content: @Composable () -> Unit)

    /** 撤下内容并关闭会话。 */
    fun hide()

    /** 手动开/关。 */
    fun toggle()

    /**
     * 把外屏"再确认一遍"：会话没了就重开，会话在但内容可能被系统换掉就重塞一份。
     *
     * 调用时机是**应用回到前台**（Activity onResume）。它必须存在，因为外屏画面
     * 挂在 Activity 上 —— 应用不可见的那段时间里，系统完全可能把会话或容器收走，
     * 而那时我们既看不到、也补不回来。
     */
    fun refresh()
}

/** Android 14 以下，或本机没有实现窗口区域扩展模块时使用。 */
class UnsupportedCoverDisplay(private val reason: String) : CoverDisplay {

    // detected 直接给 true：这种情况是**确定**不支持，不是"还没查出来"。
    private val _status = MutableStateFlow(
        CoverStatus(detected = true, available = false, presenting = false, text = reason),
    )

    override val status: StateFlow<CoverStatus> = _status

    override fun show(content: @Composable () -> Unit) = Unit

    override fun hide() = Unit

    override fun toggle() = Unit

    override fun refresh() = Unit
}
