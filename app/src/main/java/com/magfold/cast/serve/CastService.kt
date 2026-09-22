package com.magfold.cast.serve

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.magfold.cast.MainActivity
import com.magfold.cast.R
import com.magfold.cast.show.CastControl
import com.magfold.cast.show.CastPrefs

/**
 * 保活服务。**它本身不画任何东西，只有两条职责：让进程活着，以及替展出攥着那盏屏。**
 *
 * 这两条其实是同一件事的两面：屏幕锁必须由活着的进程持有（见 [ScreenWake]），
 * 而让进程活下来的正是这个服务 —— 所以「外屏常亮」只能做在这里，
 * 挂在 Activity 上（或直接用窗口 flag）在应用切走那一刻就失效了。
 *
 * 「让进程活着」这条是被 API 逼出来的：`presentContentOnWindowArea` 只接受 Activity
 * （window 1.5.1 里没有 SurfacePackage 那个重载，javap 确认过），
 * 所以外屏画面注定挂在 Activity 上。那么「应用在后台不影响显示」就等于
 * 「别让系统把进程收走、别把 Activity 销毁」—— 前台服务正是干这个的。
 *
 * 为什么不声明成 mediaPlayback：
 *   - 它只对「正在播视频」成立，而展出图片时同样需要活着，类型不符就是滥用；
 *   - 更要紧的是 Android 15 明确禁止 BOOT_COMPLETED 拉起 mediaPlayback 类型，
 *     而 specialUse 在允许名单里。这个工具迟早要在开机链条上用到它。
 *
 * 通知栏给了一个明确出口「停止展出」：划掉最近任务会被拉回来（见 [onTaskRemoved]），
 * 所以必须留一个不会被拉回来的停止方式 —— 否则就成了关不掉的牛皮糖。
 */
class CastService : Service() {

    /**
     * 外屏常亮的屏幕锁。
     *
     * 它由**服务**持有而不是由界面持有 —— 这正是关键：这把锁存在的意义就是
     * 「应用不可见之后还得继续亮」，而应用不可见时唯一确定活着的东西就是这个服务。
     * 挂在 Activity 上（或用窗口 flag）在切走那一刻就失效了，等于没做。
     */
    private val wake by lazy { ScreenWake(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "收到通知栏的「停止展出」")
            // 落盘 + 通知界面：两处都做，因为界面可能已经不在了。
            CastPrefs(this).casting = false
            CastControl.send(CastControl.Command.STOP_SHOWCASE)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        // 界面每改一次开关都会再调一次 start()，所以每次进来都对一遍屏幕锁。
        // 开关是落盘的，服务自己读得到 —— 界面可能已经不在了，不需要它把值传进来。
        syncWake()
        return START_STICKY
    }

    /**
     * 按落盘的开关对齐屏幕锁。三个条件缺一不可：
     *
     *  - **正在展出**：控制台开着但没往外屏铺东西时，没有理由不让设备睡觉；
     *  - **后台保持**：锁必须有人保着进程，否则进程被收走锁也跟着丢
     *    （表现就是"刚才是亮的，过一会儿又开始熄屏"）；
     *  - **用户没关掉常亮**。
     */
    private fun syncWake() {
        val prefs = CastPrefs(this)
        val hold = prefs.casting && prefs.keepAlive && prefs.screenOn
        Log.d(
            TAG,
            "外屏常亮 → $hold（展出=${prefs.casting} 后台保持=${prefs.keepAlive} 常亮=${prefs.screenOn}）",
        )
        wake.setHold(hold)
    }

    /** 服务没了就必须放锁，否则会在系统里留下一个没人管的常亮屏幕。 */
    override fun onDestroy() {
        wake.setHold(false)
        super.onDestroy()
    }

    /**
     * 任务被从最近任务里划掉 = Activity 已销毁 = 外屏会话已经跟着结束。
     * 只要「后台保持」「正在展出」都还开着，就把界面拉回来重建整个展出。
     *
     * 这是刻意的行为，不是失控：一个挂在墙上的展窗不该因为你顺手清了最近任务
     * 就黑掉。不想要就把控制台的「后台保持」关掉，或者用通知栏的「停止展出」。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val prefs = CastPrefs(this)
        if (prefs.casting && prefs.keepAlive) {
            Log.d(TAG, "任务被划掉，按「后台保持」重新拉起展出")
            AutoStart.launchConsole(this, AutoStart.REASON_TASK_REMOVED)
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_cast),
            // LOW：不要声音不要振动。它的作用是「让你知道它在跑、并且能一键停」。
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_cast_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.notif_title))
        .setContentText(getString(R.string.notif_text))
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setShowWhen(false)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                REQUEST_OPEN,
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(AutoStart.EXTRA_REASON, AutoStart.REASON_LAUNCHER)
                },
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .addAction(
            0,
            getString(R.string.notif_stop),
            PendingIntent.getService(
                this,
                REQUEST_STOP,
                Intent(this, CastService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    companion object {
        private const val TAG = "CastService"
        private const val CHANNEL_ID = "cast.showcase"
        private const val NOTIFICATION_ID = 4101
        private const val REQUEST_OPEN = 11
        private const val REQUEST_STOP = 12
        private const val ACTION_STOP = "com.magfold.cast.action.STOP_SHOWCASE"

        /** 由界面在「正在展出 + 后台保持」时调用。失败不抛 —— 保活失败不该拖垮展出。 */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, CastService::class.java),
                )
            }.onFailure { Log.w(TAG, "启动保活服务失败", it) }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, CastService::class.java))
            }.onFailure { Log.w(TAG, "停止保活服务失败", it) }
        }
    }
}
