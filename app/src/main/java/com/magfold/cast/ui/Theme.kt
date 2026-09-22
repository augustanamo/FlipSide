package com.magfold.cast.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 展窗的色板。
 *
 * 取自参考图：画面之外那层浅灰白（照片背景实测 #EFEFF1）当内屏纸面，
 * 墨色写字，暖棕做唯一强调色。**内屏是纸，外屏是影像** —— 所有界面元素
 * 都只在其中一种底上出现，所以每套控件只定义一套取值，不做主题切换。
 */
object Palette {
    /** 内屏纸面。 */
    val Paper = Color(0xFFEFEFF1)

    /** 墨色主文字。 */
    val Ink = Color(0xFF1B1A1C)

    /** 暖棕强调，用于选中态、极少数需要引起注意的字。 */
    val Clay = Color(0xFF7A5A42)

    /** 次要文字：45% 墨。 */
    val InkFaint = Color(0x731B1A1C)

    /** 未选中的选项字：30% 墨。比 InkFaint 更淡，用来表达"这两个里选了哪个"。 */
    val InkGhost = Color(0x4D1B1A1C)

    /** 分隔细线：12% 墨。 */
    val Hairline = Color(0x1F1B1A1C)

    /**
     * 弹窗背后的遮罩：8% 墨。
     * 纸面上不需要更重的黑幕 —— 重了就变成"模态对话框"，而设置面板该像从纸下推上来的一张卡。
     */
    val Scrim = Color(0x141B1A1C)

    /** 缩略图占位底色。 */
    val Placeholder = Color(0xFFE2E0DC)

    /** 素材还没出来时的舞台底色：近黑，不要纯黑，纯黑在折叠屏上偏硬。 */
    val Shutter = Color(0xFF101012)
}

/**
 * 控件的三件套：填充、描边、内容色。
 *
 * **纸面上不该做"玻璃"。** 参考图那层半透明白成立的前提，是它压在一张画面上 ——
 * 实测参考图里条内亮度 227~229，背后是亮度 83 的画面，有东西可透才叫玻璃。
 * 搬到纯纸面之后，它背后只有同样接近白的纸（239），于是 90% 白的填充
 * 实测出来是 253：一块**不透明**的白板，只比纸面亮 14 级（5.5%）。
 * 再叠一层投影，就变成"白卡贴纸 + 深色描边"，看着像背后压了第二张白纸。
 *
 * 所以纸面上的控件一律只留 1px 细线，跟页眉那条分隔线、三格之间那条竖线同一种语言。
 * 要加投影前先想清楚投影要表达什么 —— 内屏的纸没有"离纸多高"这回事。
 */
data class GlassTone(
    val fill: Color,
    val stroke: Color,
    val content: Color,
)

/** 压在纸面上（内屏）：无板，只有细线。 */
val GlassOnPaper = GlassTone(
    fill = Color.Transparent,
    stroke = Palette.Hairline,
    content = Palette.Ink,
)

private val CastColors = lightColorScheme(
    primary = Palette.Clay,
    onPrimary = Color.White,
    background = Palette.Paper,
    onBackground = Palette.Ink,
    surface = Palette.Paper,
    onSurface = Palette.Ink,
)

@Composable
fun CastTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CastColors, content = content)
}
