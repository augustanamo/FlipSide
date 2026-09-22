package com.magfold.cast.ui

import android.graphics.Color as AndroidColor
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.magfold.cast.media.MediaDecoder
import com.magfold.cast.media.MediaEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 动图（GIF / 动态 WebP）画面。
 *
 * **为什么不用 Compose 的 `Image`**：Compose 只吃 Bitmap/Painter，Bitmap 是"某一帧"，
 * 拿它画动图等于把动图当静态图。要让帧动起来必须交给平台那套 ——
 * `ImageDecoder.decodeDrawable` 给出 `AnimatedImageDrawable`，它自己按 GIF 的帧延迟
 * 逐帧推进（Choreographer 驱动），这是唯一"免费"的动图播放器。
 * 代价是它是个 View 世界的东西，所以借 [AndroidView] 托一个 ImageView 上来。
 *
 * 三处必须这样做的地方：
 *  - **decode 放 IO**，`start()` 放主线程。AnimatedImageDrawable 的帧回调挂在当前线程的
 *    Looper 上，从 IO 线程 start 会挂不上（画面不动，且不报错 —— 最难查的那种）。
 *  - **每次组合都重新解一份**，不共享、不缓存（见 [MediaDecoder.animatedDrawable]）：
 *    一个 AnimatedImageDrawable 同一时刻只能被一个 ImageView 持有。
 *  - **离开组合要 stop()**：否则这个 Drawable 会一直拿着帧回调空转，
 *    换片几十次就是几十个空转的动图。
 *
 * scaleType 与静态图那条路的 `ContentScale` 对齐：填满 = CENTER_CROP、适应 = FIT_CENTER。
 */
@Composable
fun AnimatedStage(
    entry: MediaEntry,
    fill: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // produceState 的 key 是 uri：换片就重新解，同一件素材重组时不会重复解。
    val drawable by produceState<Drawable?>(initialValue = null, key1 = entry.uri) {
        value = withContext(Dispatchers.IO) {
            MediaDecoder.animatedDrawable(context, entry.uri)
        }
    }

    // start/stop 都留在主线程。AnimatedImageDrawable 不是线程安全的。
    DisposableEffect(drawable) {
        val animated = drawable as? AnimatedImageDrawable
        animated?.start()
        onDispose { animated?.stop() }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            ImageView(ctx).apply {
                // 底色透明：底下还压着首帧 Bitmap，解出来之前先让它露着，避免闪黑
                setBackgroundColor(AndroidColor.TRANSPARENT)
            }
        },
        update = { view ->
            view.setImageDrawable(drawable)
            view.scaleType = if (fill) {
                ImageView.ScaleType.CENTER_CROP
            } else {
                ImageView.ScaleType.FIT_CENTER
            }
        },
    )
}
