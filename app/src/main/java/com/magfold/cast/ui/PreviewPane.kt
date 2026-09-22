package com.magfold.cast.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import com.magfold.cast.media.MediaEntry
import com.magfold.cast.window.CoverStatus
import com.magfold.cast.window.FALLBACK_COVER_ASPECT

/** 预览栏的竖向开销：标题行 + 底部尺寸行 + 上下留白。 */
private val PaneVerticalChrome = 92.dp

/** 预览栏左右各留一点边，别顶着屏幕边。 */
private val PaneSidePadding = 12.dp

/** 滑动多少距离才算"换一张"。 */
private val SwipeThreshold = 56.dp

/**
 * 按可用尺寸算出预览栏该占多宽。
 *
 * 外屏是竖幅窄条，所以预览栏也是窄条：宽度由**可用高度**乘长宽比决定，
 * 不是平分屏幕。上限取半屏多一点 —— 控制台再挤也得留得下素材网格和四格控制条。
 */
fun previewPaneWidth(availableHeight: Dp, availableWidth: Dp, aspect: Float): Dp {
    val frameHeight = (availableHeight.value - PaneVerticalChrome.value).coerceAtLeast(120f)
    val wanted = frameHeight * aspect + PaneSidePadding.value * 2f
    return minOf(wanted, availableWidth.value * 0.55f).coerceAtLeast(120f).dp
}

/**
 * 分栏预览 —— 「一侧纯看、一侧继续设置」里纯看的那一侧。
 *
 * 它画的是**和外屏完全相同的那一个 composable**（[CastStage]，一个按钮都没有），
 * 所以不存在"预览和实际不一样"的问题：差别只有画布尺寸和是否持有播放器。
 *
 * 画框按外屏真实长宽比（来自 window area 的 WindowMetrics），因此顺手解决另一个问题：
 * 一眼就能看出这张图在外屏上会被裁掉多少 —— 这正是「填满 / 适应」这个开关要判断的事。
 *
 * 三件容易被忽略的事：
 *  - 画框尺寸交给 `aspectRatio(matchHeightConstraintsFirst = true)` 自己算：
 *    先吃满高度、再按比例算宽度，宽度超了会自动退回按宽度算。不需要手写换算。
 *  - 长宽比优先用实测值，拿不到才用 [FALLBACK_COVER_ASPECT] 兜底，并在底部照实写出来。
 *  - 手势挂在画框上：左右滑动换片。预览栏是**给人看和试**的地方，所以它有交互；
 *    真实外屏那一份依然一个手势都没有。
 */
@Composable
fun PreviewPane(
    entry: MediaEntry?,
    shot: Bitmap?,
    fill: Boolean,
    player: ExoPlayer,
    attachPlayer: Boolean,
    cover: CoverStatus,
    side: Int,
    paneWidth: Dp,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val aspect = cover.coverAspect ?: FALLBACK_COVER_ASPECT
    val threshold = with(LocalDensity.current) { SwipeThreshold.toPx() }
    val shape = RoundedCornerShape(7.dp)

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(paneWidth)
            .padding(horizontal = PaneSidePadding),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 30.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (side == 0) "外屏预览 · 左" else "外屏预览 · 右",
                color = Palette.Ink,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.18.em,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "左右滑动换片",
                color = Palette.InkFaint,
                fontSize = 10.sp,
                letterSpacing = 0.08.em,
                maxLines = 1,
            )
        }

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(aspect, matchHeightConstraintsFirst = true)
                    .clip(shape)
                    .background(Palette.Shutter)
                    .border(1.dp, Palette.Hairline, shape)
                    .pointerInput(Unit) {
                        var travelled = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { travelled = 0f },
                            onHorizontalDrag = { _, delta -> travelled += delta },
                            onDragEnd = {
                                when {
                                    travelled <= -threshold -> onNext()
                                    travelled >= threshold -> onPrevious()
                                }
                            },
                        )
                    },
            ) {
                CastStage(
                    entry = entry,
                    shot = shot,
                    fill = fill,
                    player = player,
                    attachPlayer = attachPlayer,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // 视频同时只能挂在一块屏上（ExoPlayer 的硬约束）。真投到外屏时预览栏拿不到
        // 播放器，只能显示首帧 —— 这句话必须写出来，否则一帧静止画面看起来就像"预览坏了"，
        // 让人以为视频根本没在放。
        Text(
            text = if (entry?.isVideo == true && !attachPlayer) {
                "视频正在外屏播放 · 这里是首帧"
            } else {
                cover.coverLabel
            },
            color = Palette.InkFaint,
            fontSize = 10.sp,
            letterSpacing = 0.08.em,
            maxLines = 1,
        )

        Spacer(Modifier.height(16.dp))
    }
}
