package com.zzupocket.app.ui.news

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.zzupocket.app.domain.ai.MarkdownBlock
import com.zzupocket.app.domain.ai.MarkdownParser

/**
 * AI 摘要的 Markdown 渲染。
 *
 * 只支持摘要常用的子集；链接不自动打开（模型偶尔会编 URL），代码块保留等宽。
 * 这样不引入一个完整的 Markdown 依赖，也不会把模型输出当 HTML 解释。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> Text(
                    text = inlineMarkdown(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                )

                is MarkdownBlock.Paragraph -> Text(
                    text = inlineMarkdown(block.text),
                    style = textStyle,
                )

                is MarkdownBlock.Bullet -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    block.items.forEach { item ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                "•",
                                style = textStyle,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(18.dp),
                            )
                            Text(inlineMarkdown(item), style = textStyle, modifier = Modifier.weight(1f))
                        }
                    }
                }

                is MarkdownBlock.Numbered -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                "${index + 1}.",
                                style = textStyle,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(24.dp),
                            )
                            Text(inlineMarkdown(item), style = textStyle, modifier = Modifier.weight(1f))
                        }
                    }
                }

                is MarkdownBlock.Quote -> Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                ) {
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                    )
                    Text(
                        text = inlineMarkdown(block.text),
                        style = textStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }

                is MarkdownBlock.Code -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = block.code,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                    )
                }

                MarkdownBlock.Divider -> HorizontalDivider()
            }
        }
    }
}

@Composable
private fun inlineMarkdown(text: String): AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(text, codeBackground, linkColor) {
        buildAnnotatedString {
            var cursor = 0
            INLINE.findAll(text).forEach { match ->
                if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
                val bold = match.groups[1]
                val code = match.groups[2]
                val linkText = match.groups[3]
                val italic = match.groups[5]
                when {
                    bold != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(bold.value)
                    }

                    code != null -> withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeBackground,
                        ),
                    ) {
                        append(code.value)
                    }

                    linkText != null -> withStyle(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ) {
                        append(linkText.value)
                    }

                    italic != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(italic.value)
                    }
                }
                cursor = match.range.last + 1
            }
            if (cursor < text.length) append(text.substring(cursor))
        }
    }
}

private val INLINE = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[([^\\]]+)]\\(([^)]+)\\)|\\*(.+?)\\*")
