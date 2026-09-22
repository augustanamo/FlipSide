package com.magfold.cast.serve

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.magfold.cast.MainActivity

/**
 * 从后台把控制台界面拉起来这件事，以及它唯一的前提条件。
 *
 * Android 10 起后台应用不能直接 startActivity（BAL 限制），
 * 官方豁免清单里有一条是**应用已获得用户授予的 SYSTEM_ALERT_WINDOW 权限**，
 * 也就是系统设置里那句「显示在其他应用上层」。自动化类工具走的都是这条路。
 *
 * 所以「开机自动展出」不是无条件成立的，它由这个权限开关控制。
 * 没授权时这里会明确记一条日志，并且**不假装成功** —— 界面侧会把它画成待处理状态。
 *
 * 顺带一个必须记住的对比：
 *   - BOOT_COMPLETED **可以**拉起前台服务（但禁止 camera / microphone / mediaPlayback /
 *     dataSync / phoneCall / mediaProjection 这几个类型，specialUse 允许）；
 *   - BOOT_COMPLETED **不能**拉起 Activity。
 * 所以开机恢复展出的链条是「广播 → 拉起界面 → 界面可见后自己起保活服务」，
 * 而不是「广播 → 起服务」。
 */
object AutoStart {

    const val EXTRA_REASON = "com.magfold.cast.extra.REASON"

    const val REASON_LAUNCHER = "launcher"
    const val REASON_BOOT = "boot"
    const val REASON_TASK_REMOVED = "task_removed"

    /** 有没有「从后台拉起界面」的资格。 */
    fun canLaunchFromBackground(context: Context): Boolean = try {
        Settings.canDrawOverlays(context)
    } catch (error: Throwable) {
        Log.w(TAG, "查询悬浮窗权限失败，按未授权处理", error)
        false
    }

    /** 跳系统授权页。返回后调用方需要重新查一次 [canLaunchFromBackground]。 */
    fun overlaySettingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )

    /** 把控制台拉到前台。拉不起来时返回 false，调用方自己决定要不要提示。 */
    fun launchConsole(context: Context, reason: String): Boolean {
        if (!canLaunchFromBackground(context)) {
            Log.w(TAG, "缺少「显示在其他应用上层」权限，无法从后台拉起界面（reason=$reason）")
            return false
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_REASON, reason)
        }
        return runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "拉起控制台失败（reason=$reason）", it) }
            .isSuccess
    }

    private const val TAG = "AutoStart"
}
