package com.magfold.cast.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.media3.exoplayer.ExoPlayer
import com.magfold.cast.media.MediaEntry

/**
 * 舞台：**只有素材本身，什么都没有**。
 *
 * 外屏是橱窗，不是遥控器 —— 展窗挂在桌上、锁在柜里、立在店里的时候，
 * 没人站在它面前按按钮。所以这一屏上不放按钮、不放标题、不放序号、不放状态点，
 * 连给文字让路的上下压暗都省了（那层渐变本来就是为文字服务的）。
 *
 * 一切可控的东西都在内屏控制台里，见 [ConsoleScreen]。
 *
 * 同一个 composable 用在三处：真实外屏、折起后的外屏本体、以及不支持双屏
 * 机型上的内屏全屏预览。三处的差别只有 [attachPlayer]（要不要把播放器
 * 挂到这块屏上）—— 播放器同一时刻只能挂一个 Surface。
 *
 * 三条画法，按素材类型分派：
 *  - **静态图** → `Image(bitmap)`，位图由 [com.magfold.cast.media.MediaDecoder] 降采样后缓存；
 *  - **动图（GIF）** → [AnimatedStage]（Drawable 逐帧），底下照样压一张首帧位图兜底；
 *  - **视频** → 首帧位图 + [VideoStage]（PlayerView 的 shutter 透明，出画后自然盖住）。
 */
@Composable
fun CastStage(
    entry: MediaEntry?,
    shot: Bitmap?,
    fill: Boolean,
    player: ExoPlayer,
    attachPlayer: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(Palette.Shutter)) {

        // 画面。先铺位图（静态图本体 / 动图与视频的首帧），动图与视频再叠一层上层。
        //
        // 这层位图还有个容易被忽略的作用：**换片时的过渡**。ShowState 在新素材的
        // 位图解出来之前**不清空** shot，所以从图片切到视频的那一瞬间，这里画的
        // 还是上一件，而不是"先黑一下再出画"（视频出画要等解码器起播，是几百毫秒级）。
        if (shot != null) {
            Image(
                bitmap = shot.asImageBitmap(),
                contentDescription = entry?.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = if (fill) ContentScale.Crop else ContentScale.Fit,
            )
        }
        // entry 是可空的，所以这里不写 `entry?.isVideo == true` 那种写法 ——
        // 那种写法能不能被智能转换成非空，各版编译器不一致，显式判空最稳。
        val item = entry
        when {
            item != null && item.isAnimatedImage -> AnimatedStage(
                entry = item,
                fill = fill,
                modifier = Modifier.fillMaxSize(),
            )

            item != null && item.isVideo -> VideoStage(
                player = player,
                fill = fill,
                modifier = Modifier.fillMaxSize(),
                enabled = attachPlayer,
            )
        }
    }
}
