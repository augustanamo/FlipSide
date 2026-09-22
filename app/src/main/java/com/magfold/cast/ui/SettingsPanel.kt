package com.magfold.cast.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.magfold.cast.window.CoverStatus

/** 轮播可选的间隔（秒）。0 = 关。 */
val INTERVAL_VALUES = listOf(0, 5, 10, 30, 60)

/** 间隔的显示文案。控制台的元信息行也用这个，省得两处对不上。 */
fun intervalLabel(seconds: Int): String = when {
    seconds <= 0 -> "关"
    seconds >= 60 -> "1 分"
    else -> seconds.toString() + " 秒"
}

private val INTERVAL_LABELS = INTERVAL_VALUES.map { intervalLabel(it) }

/**
 * 设置面板 —— 从控制台内部推上来的一张纸，背后压一层 8% 墨的遮罩。
 *
 * 三条设计理由：
 *  1. **它挂在控制台内部**（调用方把它放进控制台那个 Box 里），所以分栏预览时
 *     它只盖住控制台那一栏，预览栏照旧可见 —— 改设置时能立刻看到效果，这正是预览的意义。
 *  2. 不用 Material 的 BottomSheet：那套自带拖拽把手、圆角容器和浅色 surface，
 *     跟本工程的纸面 + 细线语言不是一路。这里只用「一整行：左边小字标签、右边选项」，
 *     选中项靠**下划线**表达，跟页眉的分隔线同一种笔触。
 *  3. 每个开关都带一句说明它到底做了什么（尤其后台保持、开机自启），
 *     因为这两项失效时是无声的 —— 界面必须自己把话说清楚。
 */
@Composable
fun SettingsOverlay(
    visible: Boolean,
    entries: Int,
    intervalSec: Int,
    videoFullPlay: Boolean,
    fill: Boolean,
    muted: Boolean,
    playing: Boolean,
    previewSide: Int,
    keepAlive: Boolean,
    screenOn: Boolean,
    bootStart: Boolean,
    overlayGranted: Boolean,
    cover: CoverStatus,
    onSetInterval: (Int) -> Unit,
    onToggleVideoFullPlay: () -> Unit,
    onToggleFill: () -> Unit,
    onToggleMuted: () -> Unit,
    onTogglePlaying: () -> Unit,
    onFlipPreviewSide: () -> Unit,
    onToggleKeepAlive: () -> Unit,
    onToggleScreenOn: () -> Unit,
    onToggleBootStart: () -> Unit,
    onGrantOverlay: () -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Palette.Scrim)
                    .then(noRipple(onDismiss)),
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically { height -> height } + fadeIn(),
            exit = slideOutVertically { height -> height } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Palette.Paper)
                    .verticalScroll(rememberScrollState()),
            ) {
                // 上缘用重一点的一条线交代"这是一张推上来的纸"，不靠投影。
                Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.InkGhost))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "设置",
                        color = Palette.Ink,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.28.em,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "完成",
                        color = Palette.Ink,
                        fontSize = 11.sp,
                        letterSpacing = 0.14.em,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .then(noRipple(onDismiss))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }

                Hairline()

                SettingRow(label = "轮播") {
                    Segmented(
                        options = INTERVAL_LABELS,
                        selected = INTERVAL_VALUES.indexOf(intervalSec),
                        onPick = { onSetInterval(INTERVAL_VALUES[it]) },
                    )
                }

                Hairline(inset = true)

                // 「播完再切」默认开：30 秒的轮播把 2 分钟的视频从中间切走，看着像坏了。
                // 只有明确想让每件素材占同样时长的人才需要关掉它。
                SettingRow(
                    label = "视频",
                    hint = if (videoFullPlay) {
                        "视频放完才换下一件，轮播间隔对视频不生效。"
                    } else {
                        "视频到点就被切走，跟图片一样按轮播间隔。"
                    },
                ) {
                    Segmented(
                        options = listOf("播完再切", "按间隔"),
                        selected = if (videoFullPlay) 0 else 1,
                        onPick = { if ((it == 0) != videoFullPlay) onToggleVideoFullPlay() },
                    )
                }

                Hairline(inset = true)

                SettingRow(label = "画面", hint = "填满会裁掉多余部分，适应则整个画面留边显示。") {
                    Segmented(
                        options = listOf("填满", "适应"),
                        selected = if (fill) 0 else 1,
                        onPick = { if ((it == 0) != fill) onToggleFill() },
                    )
                }

                Hairline(inset = true)

                SettingRow(label = "声音") {
                    Segmented(
                        options = listOf("静音", "有声"),
                        selected = if (muted) 0 else 1,
                        onPick = { if ((it == 0) != muted) onToggleMuted() },
                    )
                }

                Hairline(inset = true)

                SettingRow(
                    label = "播放",
                    hint = "只对视频有效：暂停会把画面定格在当前这一帧，图片不受影响。",
                ) {
                    Segmented(
                        options = listOf("播放", "暂停"),
                        selected = if (playing) 0 else 1,
                        onPick = { if ((it == 0) != playing) onTogglePlaying() },
                    )
                }

                Hairline(inset = true)

                SettingRow(
                    label = "预览位置",
                    hint = "外屏在展开机身的哪一侧，分栏预览就靠哪一侧。装反了点一下换边。",
                ) {
                    Segmented(
                        options = listOf("左", "右"),
                        selected = previewSide,
                        onPick = { if (it != previewSide) onFlipPreviewSide() },
                    )
                }

                Hairline(inset = true)

                SettingRow(
                    label = "后台保持",
                    hint = "切到别的应用后让外屏继续显示。关掉后系统可能随时收走画面。",
                ) {
                    Segmented(
                        options = listOf("关", "开"),
                        selected = if (keepAlive) 1 else 0,
                        onPick = { if ((it == 1) != keepAlive) onToggleKeepAlive() },
                    )
                }

                Hairline(inset = true)

                // 常亮是「后台保持」的下游：屏幕锁得由活着的进程攥着，而让进程活着的是
                // 保活服务。所以「常亮开着、后台保持关着」这个组合是无解的，
                // 与其让它静默失效，不如把 hint 变成一句可点的提示（点一下就补上后台保持）。
                SettingRow(
                    label = "外屏常亮",
                    hint = if (screenOn && !keepAlive) {
                        "还差「后台保持」—— 屏幕锁得有活着的进程攥着，点这里打开。"
                    } else {
                        "展出时不让屏幕自动熄灭，锁屏走开也一直亮着。"
                    },
                    hintColor = if (screenOn && !keepAlive) Palette.Clay else Palette.InkFaint,
                    hintAction = if (screenOn && !keepAlive) onToggleKeepAlive else null,
                ) {
                    Segmented(
                        options = listOf("关", "开"),
                        selected = if (screenOn) 1 else 0,
                        onPick = { if ((it == 1) != screenOn) onToggleScreenOn() },
                    )
                }

                Hairline(inset = true)

                SettingRow(
                    label = "开机自启",
                    hint = if (bootStart && !overlayGranted) {
                        "还差「显示在其他应用上层」权限，点这里去授权。"
                    } else {
                        "开机后自动回到展出状态。"
                    },
                    hintColor = if (bootStart && !overlayGranted) Palette.Clay else Palette.InkFaint,
                    hintAction = if (bootStart && !overlayGranted) onGrantOverlay else null,
                ) {
                    Segmented(
                        options = listOf("关", "开"),
                        selected = if (bootStart) 1 else 0,
                        onPick = { if ((it == 1) != bootStart) onToggleBootStart() },
                    )
                }

                Hairline(inset = true)

                ClearRow(entries = entries, onClearAll = onClearAll)

                Hairline()

                Text(
                    text = cover.coverLabel + " · " + cover.text,
                    color = Palette.InkFaint,
                    fontSize = 10.sp,
                    letterSpacing = 0.06.em,
                    maxLines = 2,
                    modifier = Modifier.padding(
                        start = 24.dp,
                        end = 24.dp,
                        top = 14.dp,
                        bottom = 20.dp,
                    ),
                )
            }
        }
    }
}

/**
 * 清空素材：**两段式确认**。
 * 这是个一次性毁掉全部素材的操作，而面板本身是「随手点开看看」的地方，
 * 让第一次点击只是把按钮变成问句，代价极小、防误触却有效。
 */
@Composable
private fun ClearRow(entries: Int, onClearAll: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    val enabled = entries > 0

    SettingRow(label = "素材") {
        Text(
            text = when {
                !enabled -> "没有素材"
                confirming -> "再点一次就清空"
                else -> "清空 " + entries + " 件"
            },
            color = when {
                !enabled -> Palette.Hairline
                confirming -> Palette.Clay
                else -> Palette.Ink
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.1.em,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .then(
                    if (enabled) {
                        noRipple {
                            if (confirming) {
                                onClearAll()
                                confirming = false
                            } else {
                                confirming = true
                            }
                        }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 9.dp, vertical = 6.dp),
        )
    }
}

/** 一行设置：左边宽字距小字标签，右边控件；下面可以再挂一句说明。 */
@Composable
private fun SettingRow(
    label: String,
    hint: String? = null,
    hintColor: Color = Palette.InkFaint,
    hintAction: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 13.dp, bottom = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = Palette.InkFaint,
                fontSize = 11.sp,
                letterSpacing = 0.16.em,
                modifier = Modifier.width(72.dp),
            )
            content()
        }
        if (hint != null) {
            Spacer(Modifier.height(7.dp))
            Text(
                text = hint,
                color = hintColor,
                fontSize = 10.sp,
                letterSpacing = 0.05.em,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .then(if (hintAction != null) noRipple(hintAction) else Modifier)
                    .padding(vertical = 3.dp),
            )
        }
    }
}

/**
 * 一排选项，选中项用下划线表达。
 * 下划线是**按文字自身宽度**画的（drawBehind 拿到的是文字盒的尺寸），
 * 所以"1 分"和"10 秒"下划线等长，不会出现长短不一的怪感。
 */
@Composable
private fun Segmented(options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        options.forEachIndexed { position, label ->
            val active = position == selected
            Text(
                text = label,
                color = if (active) Palette.Ink else Palette.InkGhost,
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                letterSpacing = 0.1.em,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .then(noRipple { onPick(position) })
                    .padding(horizontal = 9.dp, vertical = 6.dp)
                    .drawBehind {
                        if (active) {
                            drawRect(
                                color = Palette.Ink,
                                topLeft = Offset(0f, size.height - 1.5f),
                                size = Size(size.width, 1.5f),
                            )
                        }
                    },
            )
        }
    }
}

/** 一条 12% 墨的分隔线。[inset] = 从左边 24dp 起笔（跟设置项的标签对齐）。 */
@Composable
private fun Hairline(inset: Boolean = false) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = if (inset) 24.dp else 0.dp)
            .height(1.dp)
            .background(Palette.Hairline),
    )
}

/**
 * 去掉水波纹的点击。
 * 纸面上没有涟漪这个概念 —— 整屏都在用细线和字色表达状态，涟漪是唯一的"材质"外来词。
 */
@Composable
private fun noRipple(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
}
