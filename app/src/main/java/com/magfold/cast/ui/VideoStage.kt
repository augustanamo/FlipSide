package com.magfold.cast.ui

import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * 视频画面。
 *
 * 用 PlayerView 而不是手搓 TextureView：适配模式（填满/适应）它已经算好了，
 * 自己写变换矩阵是白费功夫。
 *
 * **关于 @UnstableApi：机制和直觉不一样，这里把实测结论写清楚。**
 *
 * media3 的 `UnstableApi` 元注解是 `androidx.annotation.RequiresOptIn`（AndroidX 那一套），
 * **不是 Kotlin 的 `kotlin.RequiresOptIn`**（javap 看注解类自己的注解就能确认）。两个后果：
 *
 *  - **Kotlin 编译器不管它**。所以不加任何注解，`assembleDebug` 照样编过 ——
 *    本工程前两版就是这样过来的。但这不等于没问题：跑 lint（或 release 的 lintVital）
 *    会报 `UnsafeOptInUsageError`。也就是说这个约束是**静态检查层**的，不是编译层的。
 *  - **把 `@UnstableApi` 标在自己的函数上是错的**。那是把看门狗标记贴到自己身上，
 *    等于对外声明"我这个函数也不稳定"，于是调用方（CastStage / AppRoot）会被一起
 *    要求 opt-in，把问题往整条调用链上扩散。局部接受只能用 `androidx.annotation.OptIn`。
 *
 * 所以做法是：查清哪些成员真带标记，只在用到它们的这一个函数上标 androidx 的 OptIn。
 * 判定工具是 tools/check_media_api.py（按 javap 的**成员块**判定，块末的注解归块首的成员）。
 * 1.11.1 实测 PlayerView 里带标记的是 `setResizeMode` / `setShutterBackgroundColor` /
 * `setKeepContentOnPlayerReset`（连带 `getResizeMode`）；`setPlayer` / `setUseController`
 * 是稳定的。（注意：这与"整个类都没标记"不同 —— 早期版本查错过一次，别再凭印象下结论。）
 *
 * 另外两个要点：
 *  - **shutter 背景设透明**。shutter 是 PlayerView 内部那层「还没出画」的遮罩，
 *    默认纯黑；设成透明以后，它会透出下层早就画好的海报帧，于是不需要任何
 *    「首帧渲染完成」的回调来做淡入。
 *  - **onRelease 里必须把 player 摘掉**。外屏会话关闭、或从视频切到图片时，
 *    这个 View 会销毁；不摘的话播放器手里还攥着一个已经死掉的 Surface，
 *    再挂到别处就会争用。
 *
 * [enabled] 用来保证**同一时刻只有一个 PlayerView 挂着这个播放器**：
 * 折叠机身的那一瞬间、或分栏预览与外屏同时存在时，两边会同时组合到这里。
 * 让其中一边先不挂就够了，不需要更复杂的仲裁。
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun VideoStage(
    player: ExoPlayer,
    fill: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                setBackgroundColor(AndroidColor.TRANSPARENT)
                // 换片时保留上一帧，避免闪一下黑
                setKeepContentOnPlayerReset(true)
            }
        },
        update = { view ->
            view.resizeMode = if (fill) {
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            } else {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            val target = if (enabled) player else null
            if (view.player !== target) {
                view.player = target
            }
        },
        onRelease = { view -> view.player = null },
    )
}
