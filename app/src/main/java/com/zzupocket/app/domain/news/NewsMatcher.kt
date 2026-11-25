package com.zzupocket.app.domain.news

import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** 确定性规则匹配结果，不是 AI 置信度。 */
data class NewsMatch(
    val matchScore: Int,
    val matchReasons: List<String>,
)

object NewsMatcher {
    fun match(
        article: NewsArticle,
        profile: NewsProfile = NewsProfile(),
        clock: Clock = Clock.systemDefaultZone(),
    ): NewsMatch = match(
        title = article.title,
        body = article.body,
        publishedAt = article.publishedAt,
        category = article.category,
        publisher = article.publisher,
        profile = profile,
        today = LocalDate.now(clock),
    )

    fun match(
        title: String,
        body: String = "",
        publishedAt: String,
        category: NewsCategory = NewsClassifier.classify(title, body),
        publisher: String = "",
        profile: NewsProfile = NewsProfile(),
        today: LocalDate,
    ): NewsMatch {
        val interests = profile.interests.map(String::trim).filter(String::isNotEmpty)
        val normalizedTitle = normalize(title)
        val normalizedBody = normalize(body)
        val reasons = mutableListOf<String>()
        var score = 0

        interests.forEach { interest ->
            val key = normalize(interest)
            val aliases = aliases(key)
            if (aliases.any(normalizedTitle::contains)) {
                score += 40
                reasons += "命中兴趣：$interest（标题）"
            } else if (aliases.any(normalizedBody::contains)) {
                score += 20
                reasons += "命中兴趣：$interest"
            }
        }
        if (interests.isEmpty()) {
            score += 12
            reasons += "未设置兴趣画像，按时效和分类给基础分"
        }
        if (category != NewsCategory.ALL && category != NewsCategory.OTHER) {
            score += 15
            reasons += "属于${category.label}"
        }
        val age = parseDate(publishedAt)?.let { today.toEpochDay() - it.toEpochDay() }
        when {
            age == null -> Unit
            age in 0..7 -> {
                score += 30
                reasons += "近 7 天发布"
            }
            age in 0..30 -> {
                score += 20
                reasons += "近 30 天发布"
            }
        }
        if (publisher.isNotBlank() && interests.any { normalize(publisher).contains(normalize(it)) }) {
            score += 10
            reasons += "信息来源与兴趣相关"
        }
        return NewsMatch(score.coerceIn(0, 100), reasons)
    }

    /** 分类主题复用分类器的完整召回，跨分类主题再用标题/正文关键词补充。 */
    fun matchTopics(
        title: String,
        body: String,
        excerpt: String = "",
        category: NewsCategory,
        topics: Set<NewsTopic>,
    ): Set<NewsTopic> {
        val text = normalize("$title\n$excerpt\n$body")
        return topics.filterTo(mutableSetOf()) { topic ->
            topic.category == category || topic.aliases.any { alias ->
                val normalized = normalize(alias)
                if (normalized == "ai") {
                    AI_TOKEN.containsMatchIn(text)
                } else {
                    text.contains(normalized)
                }
            }
        }
    }

    private fun parseDate(value: String): LocalDate? = try {
        LocalDate.parse(value.trim())
    } catch (_: DateTimeParseException) {
        null
    }

    private fun normalize(value: String): String = value.lowercase().replace(WHITESPACE, "")

    private fun aliases(value: String): List<String> = when (value) {
        "ai", "人工智能" -> listOf("ai", "人工智能")
        "学科竞赛" -> listOf("学科竞赛", "竞赛", "大赛", "比赛")
        "创新创业" -> listOf("创新创业", "创新", "创业")
        "学术讲座" -> listOf("学术讲座", "讲座", "报告会")
        "就业实习" -> listOf("就业实习", "就业", "实习", "招聘")
        else -> listOf(value)
    }

    private val WHITESPACE = Regex("\\s+")
    private val AI_TOKEN = Regex("(?<![a-z0-9])ai(?![a-z0-9])")
}
