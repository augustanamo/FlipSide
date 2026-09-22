package com.magfold.cast.serve

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.magfold.cast.show.CastPrefs

/**
 * 开机自动展出。
 *
 * 只做一件事：判断「该不该恢复展出」，该就想办法把控制台拉起来。
 * 真正的展出由界面侧接管（界面可见后自己起保活服务、自己发起外屏会话）——
 * 因为窗口区域那套 API 只认 Activity，广播接收器不具备这个能力。
 *
 * 两个前提缺一不可，缺任何一个都只记日志不折腾：
 *   1. 上次退出时「正在展出」且用户开着「开机自动展出」；
 *   2. 有「显示在其他应用上层」权限（否则后台拉起界面会被系统静默拦下）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }

        val prefs = CastPrefs(context)
        if (!prefs.bootStart) {
            Log.d(TAG, "「开机自动展出」是关的，不拉起")
            return
        }
        if (!prefs.casting) {
            Log.d(TAG, "上次不是展出状态，不拉起")
            return
        }
        if (!AutoStart.canLaunchFromBackground(context)) {
            Log.w(
                TAG,
                "开机自启已开但缺少「显示在其他应用上层」权限，系统会拦下这次启动。" +
                    "到控制台里点一下授权即可。",
            )
            return
        }

        Log.d(TAG, "开机恢复展出（${intent.action}）")
        AutoStart.launchConsole(context, AutoStart.REASON_BOOT)
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
