package com.zzupocket.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow

/**
 * 「显示描述」开关的状态。
 *
 * 界面上的辅助文字分两层，刻意区分「提示」和「描述」：
 *  · [HintText] —— 提示（hint / help）：一句话说清这是什么、怎么操作，**永远显示**；
 *  · [DescriptionText] —— 描述（description）：原理、例外、错误原因这类补充信息，
 *    只在开关开启时绘制。关闭时**根本不进组合树**，连占位一起收掉，界面才真清爽。
 *
 * 两者都强制 `maxLines = 1`：写多了会有阅读障碍，没信息量的说明应当直接删掉，
 * 而不是换个地方继续摆着。
 *
 * 默认值 true：没挂 Provider 的预览/单测，以及 DataStore 里还没这个键的老用户，
 * 都保持「显示」的旧行为。
 */
val LocalShowDescriptions = staticCompositionLocalOf { true }

/** 常显的一行提示。只给一句话，不写用户文档。 */
@Composable
fun HintText(
    text: String,
    modifier: Modifier = Modifier,
    // 默认参数不在 composable 上下文求值，不能在这里读 MaterialTheme；用 null 到函数体里再解析
    color: Color? = null,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * 受「显示描述」控制的补充说明：原理、例外、错误原因。
 * 同样压到一行；纯废话（如图表量纲解释、点柱子能高亮这种可自行发现的交互）
 * 应当直接删掉，不要塞到这里。
 */
@Composable
fun DescriptionText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
) {
    if (!LocalShowDescriptions.current || text.isBlank()) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
