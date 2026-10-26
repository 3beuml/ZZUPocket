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
 * 配方沿袭悬浮胶囊 dock（那里是 "重一档"），但 fill 在 0.33.0-rc2 修成**近实底**：
 * rc1 曾用 surface@0.6 直叠在页底上——半透明叠同色底 = 卡面色与页面底一致，
 * 存在感只剩灰描边+影环，读作"凹陷的暗框"；而更多页因为内部 ListItem 自带
 * 不透明 surface 底，天然是"实底白卡+描边"，恰好成为用户认可的那套观感。
 * 所以现在把 fill 提到近实（@0.92），所有页面一张脸；"真透"留给将来的透明方案。
 *
 *  · 0.5dp outlineVariant 细描边 —— 轻浮片的边界，替代实底色块的对撞；
 *  · 2dp 自绘窄软影 —— 比 dock 的 6dp 更轻（卡片多而密，影子重了会成片灰晕）。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
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
