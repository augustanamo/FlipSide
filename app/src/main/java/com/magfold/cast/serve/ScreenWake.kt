package com.magfold.cast.serve

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * 展出期间的「屏幕常亮」。
 *
 * ## 为什么不用 Activity 的 FLAG_KEEP_SCREEN_ON
 *
 * 那个 flag 是**窗口级**的：它只在窗口可见时参与系统的熄灭超时计算。
 * 而这个工具的正常工作状态恰恰是「应用不可见、外屏还在放」——
 * 窗口一不可见，flag 就等于没写，屏幕照样按系统超时灭掉。
 * 这也正是「一锁屏/一回桌面，外屏就跟着黑」的一个成因（另一个是会话被收走，
 * 那个在 [WindowAreaBridge] 里处理）。
 *
 * 所以走 WakeLock：它是**进程级**的，与窗口可见性无关，
 * 恰好对上「人锁屏走开、展窗还得亮着」这个诉求。
 *
 * ## 两个刻意的选择
 *
 *  1. 级别用 `SCREEN_BRIGHT` 而不是 `SCREEN_DIM`：这是个视觉展示，
 *     被系统调暗到几乎看不见等于坏了。该省电的地方在「什么时候持有」，不在亮度。
 *  2. `setReferenceCounted(false)`：我们需要的只是「亮 / 不亮」一个布尔状态，不是计数。
 *     开着计数的话，界面每重组一次 acquire 一次、计数越堆越高，最后 release 一次
 *     根本放不掉，屏幕就永远亮着了 —— 那是个很难查的漏电 bug。
 *     关掉计数后，连续 acquire 不会叠加，release 一次即灭。
 *
 * ## 由谁持有
 *
 * **只由 [CastService] 驱动，界面不直接碰它。** 这把锁必须由活着的进程持有，
 * 而应用不可见时唯一确定还活着的东西就是那个前台服务 —— 服务没了，进程随时会被收走，
 * 锁也跟着丢（表现就是「刚才是亮的，过一会儿又开始熄屏」）。
 * 所以持有条件里必须包含「后台保持」，见 [CastService.syncWake]。
 *
 * 不点亮点屏（不加 `ACQUIRE_CAUSES_WAKEUP`）：屏幕本来就灭着的时候不必强行唤醒，
 * 那是「人不在」的场景；等人拿起手机屏幕自然亮起，再由这把锁留住。
 */
class ScreenWake(context: Context) {

    private val power: PowerManager? = context.getSystemService(PowerManager::class.java)
    private var lock: PowerManager.WakeLock? = null

    /** 按需持有或释放。重复给同一个值不做任何事，可以随手调。 */
    fun setHold(on: Boolean) {
        if (on) acquire() else release()
    }

    @Suppress("DEPRECATION")
    private fun acquire() {
        if (lock?.isHeld == true) return
        val manager = power ?: run {
            Log.w(TAG, "外屏常亮：拿不到 PowerManager，跳过")
            return
        }
        val target = lock ?: manager
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, WAKE_TAG)
            .apply { setReferenceCounted(false) }
            .also { lock = it }

        runCatching { target.acquire() }
            .onSuccess { Log.d(TAG, "外屏常亮：已持有屏幕锁") }
            .onFailure { Log.w(TAG, "外屏常亮：持有屏幕锁失败", it) }
    }

    private fun release() {
        val held = lock ?: return
        if (!held.isHeld) return
        runCatching { held.release() }
            .onSuccess { Log.d(TAG, "外屏常亮：已释放屏幕锁") }
            .onFailure { Log.w(TAG, "外屏常亮：释放屏幕锁失败", it) }
    }

    private companion object {
        const val TAG = "ScreenWake"

        /**
         * WakeLock 的标识，显示在 `adb shell dumpsys power` 里。
         * 按惯例写成「应用:用途」，排查耗电时一眼能认出是谁。
         */
        const val WAKE_TAG = "FlipSide:showcase"
    }
}
