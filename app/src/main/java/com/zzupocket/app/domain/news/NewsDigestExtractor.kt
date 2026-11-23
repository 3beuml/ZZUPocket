package com.zzupocket.app.domain.news

/** 详情页纯文本摘要；没有明确依据的字段保持为空。 */
object NewsDigestExtractor {
    private const val EXCERPT_LIMIT = 180
    private const val POINT_LIMIT = 100

    fun extract(body: String): NewsDigest {
        val paragraphs = body.lineSequence()
            .map { it.trim().replace(NOISE, "") }
            .filter { it.isNotBlank() && !isNoise(it) }
            .toList()
        if (paragraphs.isEmpty()) return NewsDigest("", emptyList())

        val points = paragraphs.filter(::isKeyParagraph)
            .map(::cleanPoint)
            .filter(String::isNotBlank)
            .distinct()
            .take(4)
            .map { it.take(POINT_LIMIT) }
        val dateParagraph = paragraphs.asSequence()
            .filter { DATE.containsMatchIn(it) }
            .filterNot { isPublicationOnly(it) }
            .sortedByDescending { datePriority(it) }
            .firstOrNull()
        val eventDate = dateParagraph?.let(::extractDate)
        val eventPlace = paragraphs.firstOrNull { PLACE_LABEL.containsMatchIn(it) || PLACE_WORD.containsMatchIn(it) }
            ?.let(::extractPlace)

        return NewsDigest(
            excerpt = paragraphs.first().take(EXCERPT_LIMIT),
            keyPoints = points,
            eventDate = eventDate,
            eventPlace = eventPlace,
        )
    }

    private fun isKeyParagraph(text: String): Boolean = KEY_LABEL.containsMatchIn(text)

    private fun cleanPoint(text: String): String = text.trim().trim('；', ';', '。')

    private fun datePriority(text: String): Int = when {
        text.contains("截止") -> 4
        text.contains("报名") && DATE.containsMatchIn(text) -> 3
        text.contains("活动时间") || text.contains("时间") -> 2
        else -> 1
    }

    private fun isPublicationOnly(text: String): Boolean =
        (text.contains("发布日期") || text.contains("发布时间") || text.contains("落款")) &&
            !text.contains("活动") && !text.contains("报名") && !text.contains("截止")

    private fun extractDate(text: String): String? {
        val match = DATE.find(text) ?: return null
        val year = match.groups["year"]?.value
        val month = (match.groups["month"]?.value ?: match.groups["month2"]!!.value).toInt().toString().padStart(2, '0')
        val day = (match.groups["day"]?.value ?: match.groups["day2"]!!.value).toInt().toString().padStart(2, '0')
        return if (year != null) "$year-$month-$day" else "$month-$day"
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
    private val DATE = Regex("(?<year>20\\d{2})\\s*[年./-]\\s*(?<month>\\d{1,2})\\s*[月./-]\\s*(?<day>\\d{1,2})\\s*日?|(?<month2>\\d{1,2})\\s*月\\s*(?<day2>\\d{1,2})\\s*日")
    private val KEY_LABEL = Regex("时间|地点|报名|截止|对象|联系|方式")
    private val PLACE_LABEL = Regex("(?:活动)?地点\\s*[：:]\\s*([^，。；;]+)")
    private val PLACE_WORD = Regex("[\\u4e00-\\u9fa5A-Za-z0-9·-]*(?:报告厅|会议室|体育馆|图书馆|教室|礼堂|大厅|广场|中心|楼)")
    private val NOISE_LINES = setOf("首页", "上一篇", "下一篇", "返回", "打印", "关闭", "扫一扫", "责任编辑")
}
