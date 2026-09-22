package com.magfold.cast

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.magfold.cast.serve.AutoStart
import com.magfold.cast.show.ShowState
import com.magfold.cast.ui.AppRoot
import com.magfold.cast.ui.CastTheme
import com.magfold.cast.window.CoverDisplay
import com.magfold.cast.window.UnsupportedCoverDisplay
import com.magfold.cast.window.WindowAreaBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var coverDisplay: CoverDisplay
    private lateinit var showState: ShowState

    /**
     * 「显示在其他应用上层」的授权状态。
     *
     * 做成流而不是一次性的布尔值，是因为它会在**离开本应用**之后改变 ——
     * 用户去系统设置里点完开关回来，onResume 要重查一遍，界面才跟得上。
     */
    private val overlayGranted = MutableStateFlow(false)

    private var notificationAsked = false

    /**
     * 系统照片选择器，一次可多选、图片视频混选。
     * 它不申请任何存储权限 —— 授予的只是「用户勾中的这几个文件」。
     */
    private val pickMedia = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_SELECTION),
    ) { uris ->
        if (uris.isNotEmpty()) showState.addUris(uris)
    }

    /** 前台服务的通知。拒绝了也不影响展出，只是通知栏看不到、也就少了「停止展出」那个出口。 */
    private val requestNotification = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> Log.d(TAG, "通知权限 = $granted") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val reason = intent?.getStringExtra(AutoStart.EXTRA_REASON) ?: AutoStart.REASON_LAUNCHER
        Log.d(TAG, "本次启动原因 = $reason")

        // 双屏幕模式：Android 14+ 且设备支持 window area 扩展（Fold + One UI 6.0+ 支持）
        coverDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            WindowAreaBridge(this).also { it.observe(lifecycleScope) }
        } else {
            UnsupportedCoverDisplay("需要 Android 14 及以上")
        }

        showState = ShowState(this)
        overlayGranted.value = AutoStart.canLaunchFromBackground(this)

        // 投屏一开，保活服务的通知就该出现，所以要一次通知权限。
        // drop(1) 是为了跳过从磁盘恢复出来的初值 —— 那是上次的状态，
        // 不该在用户刚打开应用时甩一个权限对话框。
        lifecycleScope.launch {
            showState.casting
                .drop(1)
                .collect { casting ->
                    if (casting && reason != AutoStart.REASON_BOOT) ensureNotificationPermission()
                }
        }

        setContent {
            CastTheme {
                AppRoot(
                    show = showState,
                    cover = coverDisplay,
                    overlayGranted = overlayGranted,
                    onPickMedia = {
                        pickMedia.launch(
                            // 具名参数：PickVisualMediaRequest 在 activity 1.13 里
                            // 又加了 maxItems / defaultTab 等参数，位置参数容易错位
                            PickVisualMediaRequest(
                                mediaType = ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                            ),
                        )
                    },
                    onGrantOverlay = {
                        runCatching { startActivity(AutoStart.overlaySettingsIntent(this)) }
                            .onFailure { Log.w(TAG, "打不开悬浮窗授权页", it) }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统授权页回来会走到这里 —— 这是唯一能知道用户改了没有的时机
        overlayGranted.value = AutoStart.canLaunchFromBackground(this)
        // 回到前台的第一件事：确认外屏还亮着。应用不可见的那段时间里，系统完全可能
        // 把外屏会话或容器收走（那时我们既看不到也补不回来），onResume 是唯一确定的
        // 补救时机 —— 会话没了就重开，会话在就重塞一份内容。见 CoverDisplay.refresh。
        coverDisplay.refresh()
    }

    override fun onDestroy() {
        // 配置变更时 Activity 会重建，但状态在 ShowState 里，不该跟着销毁。
        // （本 Activity 声明了 configChanges，折叠展开不会走到这里，留着是防其它配置变更。）
        //
        // 注意这里**不停保活服务**：那个服务的存在意义就是比这个界面活得久，
        // 「任务被划掉后把界面拉回来」正是靠它。
        if (!isChangingConfigurations) showState.release()
        super.onDestroy()
    }

    private fun ensureNotificationPermission() {
        if (notificationAsked) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        notificationAsked = true
        requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val TAG = "MainActivity"

        /** 系统选择器的上限是 150，取一个不超过它的整数即可。 */
        const val MAX_SELECTION = 120
    }
}
