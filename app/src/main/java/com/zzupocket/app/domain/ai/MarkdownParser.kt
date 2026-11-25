package com.zzupocket.app.domain.ai

/** AI 摘要用到的 Markdown 子集；刻意不引入完整解析器。 */
sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Bullet(val items: List<String>) : MarkdownBlock
    data class Numbered(val items: List<String>) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val language: String, val code: String) : MarkdownBlock
    data object Divider : MarkdownBlock
}

/**
 * 轻量 Markdown 解析：标题、段落、有序/无序列表、引用、围栏代码、分隔线。
 *
 * 只覆盖模型摘要实际会输出的结构；表格、图片等不处理，避免维护一个半成品
 * 完整 Markdown 引擎。行内粗体/斜体/代码由渲染层处理。
 */
object MarkdownParser {

    fun parse(markdown: String): List<MarkdownBlock> {
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<MarkdownBlock>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> i++

                trimmed.startsWith("```") -> {
                    val language = trimmed.removePrefix("```").trim()
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        code += lines[i]
                        i++
                    }
                    if (i < lines.size) i++ // 跳过收尾的 ```
                    blocks += MarkdownBlock.Code(language, code.joinToString("\n").trim())
                }

                HEADING.matches(trimmed) -> {
                    val match = HEADING.find(trimmed)!!
                    val level = match.groupValues[1].length.coerceIn(1, 6)
                    blocks += MarkdownBlock.Heading(level, match.groupValues[2].trim())
                    i++
                }

                DIVIDER.matches(trimmed) -> {
                    blocks += MarkdownBlock.Divider
                    i++
                }

                BULLET.matches(line) -> {
                    val items = mutableListOf<String>()
                    while (i < lines.size && BULLET.matches(lines[i])) {
                        items += BULLET.find(lines[i])!!.groupValues[1].trim()
                        i++
                        // 缩进的续行并入上一条
                        while (i < lines.size && lines[i].startsWith("  ") && lines[i].trim().isNotEmpty() &&
                            !BULLET.matches(lines[i]) && !NUMBERED.matches(lines[i])
                        ) {
                            items[items.lastIndex] += " " + lines[i].trim()
                            i++
                        }
                    }
                    blocks += MarkdownBlock.Bullet(items)
                }

                NUMBERED.matches(line) -> {
                    val items = mutableListOf<String>()
                    while (i < lines.size && NUMBERED.matches(lines[i])) {
                        items += NUMBERED.find(lines[i])!!.groupValues[1].trim()
                        i++
                    }
                    blocks += MarkdownBlock.Numbered(items)
                }

                trimmed.startsWith(">") -> {
                    val quote = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        quote += lines[i].trim().removePrefix(">").trim()
                        i++
                    }
                    blocks += MarkdownBlock.Quote(quote.joinToString("\n"))
                }

                else -> {
                    val paragraph = mutableListOf<String>()
                    while (i < lines.size) {
                        val next = lines[i]
                        if (next.isBlank() || next.trim().startsWith("```") ||
                            HEADING.matches(next.trim()) || DIVIDER.matches(next.trim()) ||
                            BULLET.matches(next) || NUMBERED.matches(next) ||
                            next.trim().startsWith(">")
                        ) break
                        paragraph += next.trim()
                        i++
                    }
                    if (paragraph.isNotEmpty()) {
                        blocks += MarkdownBlock.Paragraph(paragraph.joinToString(" "))
                    }
                }
            }
        }
        return blocks
    }

    private val HEADING = Regex("^(#{1,6})\\s+(.+)$")
    private val BULLET = Regex("^\\s*(?:[-*+]|•)\\s+(.+)$")
    private val NUMBERED = Regex("^\\s*\\d+[.)、]\\s*(.+)$")
    private val DIVIDER = Regex("^\\s*([-*_]\\s*){3,}$")
}
