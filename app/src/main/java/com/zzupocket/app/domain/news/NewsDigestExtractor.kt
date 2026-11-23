package com.zzupocket.app.domain.news

/** 详情页纯文本摘要；没有明确依据的字段保持为空。 */
object NewsDigestExtractor {
    private const val EXCERPT_LIMIT = 180
    private const val POINT_LIMIT = 100
    private const val VALUE_LIMIT = 80

    fun extract(body: String): NewsDigest {
        val paragraphs = body.lineSequence()
            .map { it.trim().replace(NOISE, "") }
            .filter { it.isNotBlank() && !isNoise(it) }
            .toList()
        if (paragraphs.isEmpty()) return NewsDigest("", emptyList())

        val points = buildKeyPoints(paragraphs)

        // 落款日期通常在最后两段；只在尾部且没有活动语境时按落款处理。
        val dateParagraph = paragraphs.withIndex()
            .filter { (_, text) -> DATE.containsMatchIn(text) && !isPublicationOnly(text) }
            .filterNot { (index, text) ->
                isBareDate(text) && index >= paragraphs.size - 3
            }
            .sortedByDescending { (_, text) -> datePriority(text) }
            .firstOrNull()
            ?.value
        val eventDate = dateParagraph?.let(::extractDate)

        val eventPlace = paragraphs.asSequence()
            .filterNot { headingLabel(it) != null }
            .filter { it.length <= 60 }
            .firstOrNull { PLACE_LABEL.containsMatchIn(it) }
            ?.let(::extractPlace)
            ?: paragraphs.asSequence()
                .filterNot { headingLabel(it) != null }
                .filter { it.length in 4..30 }
                .filterNot { INSTITUTION.containsMatchIn(it) }
                .firstOrNull { PLACE_WORD.containsMatchIn(it) }
                ?.let(::extractPlace)

        val excerpt = paragraphs.firstOrNull {
            it.length >= 20 && !(it.length < 60 && (it.endsWith('：') || it.endsWith(':')))
        } ?: paragraphs.first()

        return NewsDigest(
            excerpt = excerpt.take(EXCERPT_LIMIT),
            keyPoints = points,
            eventDate = eventDate,
            eventPlace = eventPlace,
        )
    }

    /**
     * 先认「标题 + 下一行值」和「标签：值」，再补电话、邮箱和日期句。
     *
     * 目录行（三、参与对象）本身没有内容，必须和下一行一起读；纯长段落里偶然出现的
     * “报名”不算要点，避免把整段流程说明塞进卡片。
     */
    private fun buildKeyPoints(paragraphs: List<String>): List<String> {
        val points = LinkedHashMap<String, Point>()
        val labelCounts = mutableMapOf<String, Int>()

        fun add(label: String, rawValue: String) {
            val source = when (label) {
                "邮箱" -> EMAIL.find(rawValue)?.value ?: rawValue
                "电话" -> PHONE.find(rawValue)?.value ?: rawValue
                "对象", "方式", "主题", "内容" -> rawValue.substringBefore('。').trim()
                else -> rawValue
            }
            val value = cleanValue(source)
            if (value.length < 2 || isBareDate(value)) return
            val max = if (label == "时间") 2 else 1
            if ((labelCounts[label] ?: 0) >= max) return
            val text = if (label.isBlank()) value else "$label：$value"
            if (text.length > POINT_LIMIT || points.containsKey(text)) return
            points[text] = Point(label, value)
            labelCounts[label] = (labelCounts[label] ?: 0) + 1
        }

        var index = 0
        while (index < paragraphs.size) {
            val text = paragraphs[index]
            val heading = headingLabel(text)
            if (heading != null) {
                val next = paragraphs.getOrNull(index + 1)
                if (next != null) {
                    val labeled = labelValue(next)
                    if (labeled != null) {
                        add(labeled.first, labeled.second)
                        index += 2
                        continue
                    }
                    val phone = PHONE.find(next)
                    if (phone != null) {
                        add("电话", phone.value)
                        index += 2
                        continue
                    }
                    val email = EMAIL.find(next)
                    if (email != null) {
                        add("邮箱", email.value)
                        index += 2
                        continue
                    }
                    if (!isSubHeading(next) && headingLabel(next) == null && next.length <= 120) {
                        add(heading, next)
                        index += 2
                        continue
                    }
                }
                index++
                continue
            }

            val labeled = labelValue(text)
            if (labeled != null) {
                add(labeled.first, labeled.second)
            } else {
                specialValue(text)?.let { (label, value) -> add(label, value) }
            }
            index++
        }

        if (points.isEmpty()) {
            paragraphs.asSequence()
                .filter { it.length in 8..120 && !isSubHeading(it) && headingLabel(it) == null }
                .filter { DATE.containsMatchIn(it) || PLACE_LABEL.containsMatchIn(it) }
                .take(2)
                .forEach { add("", it) }
        }

        return points.values
            .sortedByDescending { priority(it.label) }
            .take(4)
            .map { it.text }
    }

    private data class Point(val label: String, val value: String) {
        val text: String get() = if (label.isBlank()) value else "$label：$value"
    }

    private fun priority(label: String): Int = when (label) {
        "时间" -> 5
        "地点", "电话" -> 4
        "对象", "邮箱" -> 3
        "费用", "方式", "联系" -> 2
        "主题", "内容" -> 1
        else -> 2
    }

    /** 只把「短标题」认成标题；带具体内容的冒号行交给 [labelValue]。 */
    private fun headingLabel(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.length > 24) return null
        val colon = trimmed.indexOfAny(charArrayOf('：', ':'))
        if (colon >= 0 && trimmed.substring(colon + 1).isNotBlank()) return null
        val noNumber = NUMBER_PREFIX.replace(trimmed, "").trim().trimEnd('：', ':').trim()
        if (noNumber.isBlank() || noNumber.length > 14) return null
        return normalizeLabel(noNumber)
    }

    private fun labelValue(text: String): Pair<String, String>? {
        val match = LABEL_VALUE.matchEntire(text.trim()) ?: return null
        val label = normalizeLabel(match.groupValues[1]) ?: return null
        val value = match.groupValues[2].trim().trimEnd('。', '；', ';', '，', ',')
        if (value.isBlank()) return null
        return label to value
    }

    private fun normalizeLabel(raw: String): String? {
        val label = raw.trim()
        return when {
            label.contains("时间") || label.contains("截止") || label.contains("征集") -> "时间"
            label.contains("地点") -> "地点"
            label.contains("对象") || label.contains("资格") -> "对象"
            label.contains("电话") || label.contains("联系方式") -> "电话"
            label.contains("联系人") || label.contains("联系") || label.contains("群") -> "联系"
            label.contains("方式") || label.contains("渠道") -> "方式"
            label.contains("主题") -> "主题"
            label.contains("费用") || label.contains("报名费") -> "费用"
            label.contains("邮箱") -> "邮箱"
            label.contains("内容") -> "内容"
            else -> null
        }
    }

    private fun specialValue(text: String): Pair<String, String>? {
        EMAIL.find(text)?.let { return "邮箱" to it.value }
        PHONE.find(text)?.let { return "电话" to it.value }
        if (!DATE.containsMatchIn(text) || isBareDate(text)) return null
        // 只有截止/报名/征集/公示等有行动含义的日期句才当要点；
        // 活动介绍里的普通举行日期交给 eventDate，避免重复一条长段落。
        if (!DEADLINE_CONTEXT.containsMatchIn(text)) return null
        val sentence = text.split(SENTENCE_END)
            .firstOrNull { DATE.containsMatchIn(it) && it.trim().length >= 6 }
            ?.trim()
            ?: return null
        return "" to sentence
    }

    private fun cleanValue(text: String): String {
        val cleaned = NUMBER_PREFIX.replace(text.trim(), "")
            .trim()
            .trim('。', '；', ';', '，', ',', '：', ':')
            .replace(Regex("\\s+"), " ")
        if (cleaned.length <= VALUE_LIMIT) return cleaned
        return cleaned.take(VALUE_LIMIT).trimEnd('，', ',', '、') + "…"
    }

    private fun isSubHeading(text: String): Boolean {
        val trimmed = text.trim()
        return NUMBER_PREFIX.containsMatchIn(trimmed) &&
            !trimmed.contains('：') && !trimmed.contains(':') &&
            trimmed.length <= 30
    }

    /** 单独一行日期、或“机构名 + 日期”的落款，不当正文要点/活动日期。 */
    private fun isBareDate(text: String): Boolean {
        val trimmed = text.trim().trim('。', '；', ';', '，', ',')
        if (BARE_DATE.matches(trimmed)) return true
        return DATE.containsMatchIn(trimmed) &&
            !TIME.containsMatchIn(trimmed) &&
            trimmed.length <= 24 &&
            !KEY_CONTEXT.containsMatchIn(trimmed)
    }

    private fun datePriority(text: String): Int = when {
        text.contains("截止") -> 5
        text.contains("报名") || text.contains("征集") -> 4
        text.contains("活动时间") || text.contains("报告时间") || text.contains("时间") -> 3
        else -> 1
    }

    private fun isPublicationOnly(text: String): Boolean =
        (text.contains("发布日期") || text.contains("发布时间") || text.contains("落款")) &&
            !text.contains("活动") && !text.contains("报名") && !text.contains("截止")

    private fun extractDate(text: String): String? {
        val match = DATE.find(text) ?: return null
        val year = match.groups["year"]?.value ?: match.groups["y2"]?.value
        val month = match.groups["month"]?.value
            ?: match.groups["m2"]?.value
            ?: match.groups["m3"]?.value
            ?: return null
        val day = match.groups["day"]?.value
            ?: match.groups["d2"]?.value
            ?: match.groups["d3"]?.value
            ?: return null
        val mm = month.toInt().toString().padStart(2, '0')
        val dd = day.toInt().toString().padStart(2, '0')
        return if (year != null) "$year-$mm-$dd" else "$mm-$dd"
    }

    private fun extractPlace(text: String): String? {
        val labeled = PLACE_LABEL.find(text)?.groupValues?.get(1)
            ?.substringBefore('。')?.substringBefore('；')?.substringBefore(';')?.trim()
        if (!labeled.isNullOrBlank()) return labeled
        return PLACE_WORD.find(text)?.value
    }

    private fun isNoise(text: String): Boolean = NOISE_LINES.any { text == it } ||
        text.startsWith("分享到") || text.startsWith("版权所有")

    private val NOISE = Regex("[\\u00a0\\u3000]+")
    private val NUMBER_PREFIX = Regex("^[（(]?[一二三四五六七八九十0-9]+[）)、.．]\\s*")
    private val LABEL_VALUE = Regex(
        "^(?:[（(]?[一二三四五六七八九十0-9]+[）)、.．]\\s*)?([\\u4e00-\\u9fa5A-Za-z]{2,12})\\s*[：:]\\s*(.+)$",
    )
    private val DATE = Regex(
        "(?<year>20\\d{2})\\s*年\\s*(?<month>\\d{1,2})\\s*月\\s*(?<day>\\d{1,2})\\s*日" +
            "|(?<y2>20\\d{2})[-/.](?<m2>\\d{1,2})[-/.](?<d2>\\d{1,2})" +
            "|(?<m3>\\d{1,2})\\s*月\\s*(?<d3>\\d{1,2})\\s*日",
    )
    private val TIME = Regex("\\d{1,2}[:：]\\d{2}")
    private val BARE_DATE = Regex(
        "^20\\d{2}\\s*[年./-]\\s*\\d{1,2}\\s*[月./-]\\s*\\d{1,2}\\s*日?$|^\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日$",
    )
    private val PLACE_LABEL = Regex("(?:活动)?地点\\s*[：:]\\s*([^，。；;]+)")
    private val PLACE_WORD = Regex(
        "[\\u4e00-\\u9fa5A-Za-z0-9·-]*(?:报告厅|会议室|体育馆|图书馆|教室|礼堂|大厅|广场|中心|楼)",
    )
    private val EMAIL = Regex("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}")
    private val PHONE = Regex("(?:1[3-9]\\d{9}|0\\d{2,3}[-—]?\\d{7,8})")
    private val INSTITUTION = Regex("大学|学院|服务中心|党委|办公室|公司|集团|协会|委员会")
    private val KEY_CONTEXT = Regex("活动|报名|截止|征集|时间|地点")
    private val DEADLINE_CONTEXT = Regex("截止|报名|征集|公示|测评|提交|申报")
    private val SENTENCE_END = Regex("[。；;]")
    private val NOISE_LINES = setOf(
        "首页", "上一篇", "下一篇", "返回", "打印", "关闭", "扫一扫", "责任编辑",
    )
}
