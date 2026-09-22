package com.magfold.cast.show

import android.content.Context

/**
 * 展窗的开关持久化。
 *
 * 为什么这些要落盘：这个工具的核心诉求是「挂上去就不用管了」——
 * 上次是展出状态，开机之后就该自己回到展出状态，而不是每次都重新点一遍。
 * 所以「是否投屏」「填满/适应」「轮播间隔」「后台保持」「开机自动展出」都要活过进程重启。
 *
 * 素材清单不在这里，那个在 [com.magfold.cast.media.SelectionStore]。
 */
class CastPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 是否投到外屏。默认关 —— 没选素材就往外屏铺东西没意义。 */
    var casting: Boolean
        get() = prefs.getBoolean(KEY_CASTING, false)
        set(value) = prefs.edit().putBoolean(KEY_CASTING, value).apply()

    /** true = 填满（裁切），false = 适应（留边）。 */
    var fill: Boolean
        get() = prefs.getBoolean(KEY_FILL, true)
        set(value) = prefs.edit().putBoolean(KEY_FILL, value).apply()

    /** 是否静音。默认静音 —— 这是橱窗展示，不是播放器。 */
    var muted: Boolean
        get() = prefs.getBoolean(KEY_MUTED, true)
        set(value) = prefs.edit().putBoolean(KEY_MUTED, value).apply()

    /**
     * 轮播间隔（秒）。**0 = 关**。
     *
     * 默认关：展窗该由人决定放哪一张，自动换片是"要才开"的能力，
     * 不该第一次打开就自己动起来。
     */
    var intervalSec: Int
        get() = prefs.getInt(KEY_INTERVAL, 0)
        set(value) = prefs.edit().putInt(KEY_INTERVAL, value).apply()

    /** 内屏分栏预览是否开着（一侧看效果、一侧继续设置）。 */
    var previewing: Boolean
        get() = prefs.getBoolean(KEY_PREVIEW, false)
        set(value) = prefs.edit().putBoolean(KEY_PREVIEW, value).apply()

    /**
     * 视频是否「播完再切」。默认开。
     *
     * 开着时轮播间隔对视频**不生效**：视频一直放到尾（`STATE_ENDED`）才换下一件，
     * 否则 30 秒的轮播会把 2 分钟的视频从中间硬切走。
     * 关掉则回到"按间隔硬切"，适合只想让每件素材占同样时长的排布。
     */
    var videoFullPlay: Boolean
        get() = prefs.getBoolean(KEY_VIDEO_FULL, true)
        set(value) = prefs.edit().putBoolean(KEY_VIDEO_FULL, value).apply()

    /** 预览窗格靠在展开机身的哪一侧：0 = 左，1 = 右。 */
    var previewSide: Int
        get() = prefs.getInt(KEY_PREVIEW_SIDE, 0)
        set(value) = prefs.edit().putInt(KEY_PREVIEW_SIDE, value).apply()

    /**
     * 后台保持：应用不可见时也维持进程存活（前台服务），
     * 这样外屏会话和播放器都不会被回收。默认开 —— 这是「挂上去不管」的前提。
     */
    var keepAlive: Boolean
        get() = prefs.getBoolean(KEY_KEEP_ALIVE, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_ALIVE, value).apply()

    /**
     * 外屏常亮：展出期间不让屏幕按系统超时熄灭。默认开 ——
     * 一个自己会熄的展窗等于没展出，这是用户按一次就得回来按一次的功能。
     *
     * **它需要 [keepAlive] 也开着**：屏幕锁必须由活着的进程持有，
     * 而让进程活下来的正是那个保活服务（见 `serve/CastService`、`serve/ScreenWake`）。
     * 界面侧会把「开着但后台保持关着」显式画成待处理状态，不让它静默失效。
     */
    var screenOn: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_SCREEN_ON, value).apply()

    /**
     * 开机自动展出。默认开，但**真正生效还需要系统「显示在其他应用上层」权限** ——
     * Android 10 起后台拉起界面必须靠这个豁免，没有它广播发出去也白搭。
     * 界面侧会把「开着但没授权」显式画成待处理状态，不让它静默失效。
     */
    var bootStart: Boolean
        get() = prefs.getBoolean(KEY_BOOT_START, true)
        set(value) = prefs.edit().putBoolean(KEY_BOOT_START, value).apply()

    private companion object {
        const val PREFS = "cast.switches"
        const val KEY_CASTING = "casting"
        const val KEY_FILL = "fill"
        const val KEY_MUTED = "muted"
        const val KEY_INTERVAL = "intervalSec"
        const val KEY_PREVIEW = "previewing"
        const val KEY_PREVIEW_SIDE = "previewSide"
        const val KEY_VIDEO_FULL = "videoFullPlay"
        const val KEY_KEEP_ALIVE = "keepAlive"
        const val KEY_SCREEN_ON = "screenOn"
        const val KEY_BOOT_START = "bootStart"
    }
}
