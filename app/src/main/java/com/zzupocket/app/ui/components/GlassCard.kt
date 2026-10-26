package com.zzupocket.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * 全应用统一的「玻璃卡」容器 —— 今天页/更多页/电费/校园卡等所有功能块的底座。
 *
 * 配方是从悬浮胶囊 dock 降档下放的（那里是 "重一档"，这里是 "轻一档"），三个来源收获了教训：
 *
 *  · 半透明 surface 而不是 surfaceContainer 系实底——保留主题色调，不再发"死灰"；
 *    非壁纸页上它叠在同色窗口底，所以颜色会与页面底一致，卡的存在感交给描边+影来表达；
 *  · 0.5dp outlineVariant 细描边——轻浮片的边界，替代实底色块的对撞；
 *  · 2dp 自绘窄软影——比 dock 的 6dp 更轻（卡片多而密，影子重了会成片灰晕，
 *    dock 只有一枚所以能承受）。
 *
 * TODO(后续): 若用户希望卡片带主题色气息，在 fill 上叠一层 primary@2~3% 微 tint
 *  （仍同色系）——等真机验收纯玻璃效果后再定，不预设。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(
            0.5.dp,
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.16f),
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                ),
            ),
        ),
        modifier = modifier.shadow(
            elevation = 2.dp,
            shape = RoundedCornerShape(16.dp),
            clip = false,
        ),
    ) {
        Box(content = { content() })
    }
}
