package com.magfold.cast.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.magfold.cast.media.MediaDecoder
import com.magfold.cast.media.MediaEntry
import com.magfold.cast.window.CoverStatus

/**
 * 内屏控制台 —— **这个工具唯一能操作的地方**。
 *
 * 外屏是纯展示（只放画面，一个按钮都没有），所以所有操作都必须在这里找得到。
 * 但"都在这里"不等于"都摊在主界面上"：主界面只留三样东西 ——
 * 素材、上一张/下一张、两个开关（投屏 / 预览），其余全部收进 [SettingsOverlay]。
 * 理由是这台设备多半立在桌上，用户是**偶尔过来调一下**，不是长期盯着一张设置表。
 *
 * 版式语言照参考图来：宽字距大写字，细到几乎看不见的分隔线，大留白，
 * 三/四段式控制条压底。整体像一页版式设计，而不是一个设置页。
 *
 * 这个 composable 会被放在两种宽度里：独占内屏（非预览态）或只占一栏（分栏预览态）。
 * 所以它内部**没有**任何按屏幕宽度写死的尺寸 —— 网格用自适应列数，
 * 设置面板也挂在自身 Box 里（因此分栏时它只盖住控制台这一栏）。
 */
@Composable
fun ConsoleScreen(
    entries: List<MediaEntry>,
    index: Int,
    casting: Boolean,
    previewing: Boolean,
    presentable: Boolean,
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
    settingsOpen: Boolean,
    onPick: () -> Unit,
    onSelect: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleCasting: () -> Unit,
    onTogglePreviewing: () -> Unit,
    onTogglePlaying: () -> Unit,
    onSettingsChange: (Boolean) -> Unit,
    onSetInterval: (Int) -> Unit,
    onToggleVideoFullPlay: () -> Unit,
    onToggleFill: () -> Unit,
    onToggleMuted: () -> Unit,
    onFlipPreviewSide: () -> Unit,
    onToggleKeepAlive: () -> Unit,
    onToggleScreenOn: () -> Unit,
    onToggleBootStart: () -> Unit,
    onGrantOverlay: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = entries.getOrNull(index)
    // 设置里有**两处**会因缺「显示在其他应用上层」而静默失效：开机自启，
    // 以及后台保持里的「划掉任务自动拉回」（CastService.onTaskRemoved 也走 AutoStart）。
    // 任一处开着且没授权就算待处理 —— 只盯开机自启的话，第二处就漏了。
    val needsOverlay = !overlayGranted && (bootStart || keepAlive)

    Box(modifier = modifier.fillMaxSize().background(Palette.Paper)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            // ---- 头部：应用标记、素材入口、设置入口 ----
            Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "FLIPSIDE",
                        color = Palette.Ink,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Normal,
                        letterSpacing = 0.24.em,
                    )
                    Spacer(Modifier.weight(1f))
                    PillButton(text = "＋ 素材", onClick = onPick)
                    Spacer(Modifier.width(8.dp))
                    // 设置开着但缺权限时整颗按钮转暖棕：静默失效的开关必须自己冒头
                    PillButton(
                        text = "设置",
                        attention = needsOverlay,
                        onClick = { onSettingsChange(true) },
                    )
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    text = cover.text,
                    color = if (cover.available) Palette.Clay else Palette.InkFaint,
                    fontSize = 11.sp,
                    letterSpacing = 0.1.em,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = metaText(entries.size, index, intervalSec),
                    color = Palette.InkFaint,
                    fontSize = 11.sp,
                    letterSpacing = 0.06.em,
                    maxLines = 1,
                )

                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline))
            }

            // ---- 素材网格 ----
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (entries.isEmpty()) {
                    EmptyState(onPick = onPick)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 96.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 24.dp, end = 24.dp, top = 18.dp, bottom = 18.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(
                            entries,
                            key = { _, entry -> entry.uri.toString() },
                        ) { position, entry ->
                            ThumbCell(
                                entry = entry,
                                position = position,
                                selected = position == index,
                                onSelect = { onSelect(position) },
                                onRemove = { onRemove(position) },
                            )
                        }
                    }
                }
            }

            // ---- 底部：四格控制条 ----
            // 只留"立刻要看结果"的四件事：翻片、投外屏、预览、翻片。
            // 轮播、填满、静音这些"改一次就长期有效"的全在设置里。
            Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp)) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline))
                Spacer(Modifier.height(12.dp))
                GlassRow(
                    labels = listOf(
                        "上一张",
                        castingLabel(casting, presentable),
                        previewLabel(previewing),
                        "下一张",
                    ),
                    tone = GlassOnPaper,
                    modifier = Modifier.fillMaxWidth(),
                    activeFlags = listOf(false, casting, previewing, false),
                    onPick = { position ->
                        when (position) {
                            0 -> onPrevious()
                            1 -> onToggleCasting()
                            2 -> onTogglePreviewing()
                            else -> onNext()
                        }
                    },
                )
            }
        }

        // 设置面板挂在控制台**内部**：分栏预览时它只盖住这一栏，
        // 预览栏照旧可见 —— 改设置能立刻看到效果，这正是预览存在的意义。
        SettingsOverlay(
            visible = settingsOpen,
            entries = entries.size,
            intervalSec = intervalSec,
            videoFullPlay = videoFullPlay,
            fill = fill,
            muted = muted,
            playing = playing,
            previewSide = previewSide,
            keepAlive = keepAlive,
            screenOn = screenOn,
            bootStart = bootStart,
            overlayGranted = overlayGranted,
            cover = cover,
            onSetInterval = onSetInterval,
            onToggleVideoFullPlay = onToggleVideoFullPlay,
            onToggleFill = onToggleFill,
            onToggleMuted = onToggleMuted,
            onTogglePlaying = onTogglePlaying,
            onFlipPreviewSide = onFlipPreviewSide,
            onToggleKeepAlive = onToggleKeepAlive,
            onToggleScreenOn = onToggleScreenOn,
            onToggleBootStart = onToggleBootStart,
            onGrantOverlay = onGrantOverlay,
            onClearAll = onClear,
            onDismiss = { onSettingsChange(false) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 元信息：素材件数、当前第几张、轮播间隔。 */
private fun metaText(count: Int, index: Int, intervalSec: Int): String {
    if (count == 0) return "还没有素材"
    val carousel = if (intervalSec > 0) "轮播 " + intervalLabel(intervalSec) else "轮播关"
    return count.toString() + " 件素材 · 当前 " + (index + 1) + "/" + count + " · " + carousel
}

/** 中间偏左那格：投屏开着就是「收起」，没开就看这台机器能不能真投。 */
private fun castingLabel(casting: Boolean, presentable: Boolean): String = when {
    casting -> "收起外屏"
    presentable -> "投到外屏"
    else -> "铺满内屏"
}

private fun previewLabel(previewing: Boolean): String =
    if (previewing) "收起预览" else "预览外屏"

@Composable
private fun EmptyState(onPick: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "还没有素材",
                color = Palette.Ink,
                fontSize = 16.sp,
                letterSpacing = 0.08.em,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "从相册挑几张图片或视频，铺在外屏上",
                color = Palette.InkFaint,
                fontSize = 12.sp,
                letterSpacing = 0.05.em,
            )
            Spacer(Modifier.height(22.dp))
            GlassRow(
                labels = listOf("从相册选择"),
                tone = GlassOnPaper,
                modifier = Modifier.width(210.dp),
                onPick = { onPick() },
            )
        }
    }
}

/**
 * 网格里的一格。
 *
 * 选中格的右上角会出现一个小叉 —— 用「选中才可删」而不是长按菜单，
 * 是为了把删除能力摆在明面上，又不让每张缩略图上都挂个常驻按钮。
 */
@Composable
private fun ThumbCell(
    entry: MediaEntry,
    position: Int,
    selected: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    var thumb by remember(entry.uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(entry.uri) {
        thumb = MediaDecoder.thumbnail(context, entry)
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(3.dp))
            .background(Palette.Placeholder)
            .clickable(onClick = onSelect),
    ) {
        val bitmap = thumb
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = entry.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        // 视频角标：左下角一个小实心三角
        if (entry.isVideo) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp)
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(Color(0xD9000000)),
                contentAlignment = Alignment.Center,
            ) {
                Text("▶", color = Color.White, fontSize = 6.sp)
            }
        }

        // 动图角标：GIF 也是"会动的东西"，但它不走播放器、没有时长、外屏上是一直播下去的，
        // 所以用字标而不是那个三角 —— 两种会动的素材不该在网格里长得一样。
        if (entry.isAnimatedImage) {
            Text(
                text = "GIF",
                color = Color.White,
                fontSize = 6.sp,
                letterSpacing = 0.08.em,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xD9000000))
                    .padding(horizontal = 3.dp, vertical = 1.5.dp),
            )
        }

        if (selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .border(1.5.dp, Palette.Ink, RoundedCornerShape(3.dp)),
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color(0xF21B1A1C))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Text("×", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

/** 细边框胶囊按钮。[attention] = 有件事在等着用户处理（转到暖棕）。 */
@Composable
private fun PillButton(text: String, attention: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .border(
                width = 1.dp,
                color = if (attention) Palette.Clay else Palette.Hairline,
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            color = if (attention) Palette.Clay else Palette.Ink,
            fontSize = 12.sp,
            letterSpacing = 0.08.em,
        )
    }
}
