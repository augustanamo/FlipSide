package com.magfold.cast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** 控制条的高度。参考图里按钮组占屏高约 6%，在折叠屏上折算是 56dp 上下。 */
private val GlassHeight = 56.dp
private val GlassCorner = 18.dp

/** 非激活段的字色：把内容色压到 42%，比加一条分隔线更能说明"这段是状态、不是按钮"。 */
private const val IDLE_ALPHA = 0.42f

/**
 * 三段式控制条 —— 参考图里那一排「SHOW ALL / SHOP / IDENTITY」。
 *
 * 它是一整块圆角矩形，内部等分若干段，段与段之间只有一条 1px 竖线，
 * 而不是几个各自独立的小按钮。这个区别就是"编辑排版感"和"普通设置项"的区别。
 *
 * [activeFlags] 标记哪几段是"此刻生效的状态"（投屏中、预览中）。
 * 用**段内文字深浅**表达，不加勾、不加背景 —— 一整条里出现两种深浅，读起来就是
 * "这两段是开关，另外两段是动作"。允许同时有多个激活段（投屏 + 预览可以并存）。
 *
 * **不要给它加投影。** 早先这里有一层 10dp 的 elevation 投影，本意是让它浮起来，
 * 实测在纸面上等于给一块白板描了一圈 20% 黑的边：白板 + 深色描边，看起来就是
 * "纸上贴了一张白卡"，而不是纸面自己的组件。内屏的纸不需要"高度"这个概念，
 * 所以这里只用细线，层级靠线的疏密表达。
 */
@Composable
fun GlassRow(
    labels: List<String>,
    tone: GlassTone,
    modifier: Modifier = Modifier,
    activeFlags: List<Boolean> = emptyList(),
    onPick: (Int) -> Unit = {},
) {
    val shape = RoundedCornerShape(GlassCorner)
    Row(
        modifier = modifier
            .clip(shape)
            .background(tone.fill)
            .border(1.dp, tone.stroke, shape)
            .height(GlassHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { position, label ->
            if (position > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(tone.stroke),
                )
            }
            val active = activeFlags.getOrNull(position) ?: false
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onPick(position) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (active) tone.content else tone.content.copy(alpha = IDLE_ALPHA),
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    letterSpacing = 0.14.em,
                    maxLines = 1,
                )
            }
        }
    }
}
