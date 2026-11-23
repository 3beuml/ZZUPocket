package com.zzupocket.app.domain.news

/** 官网栏目。列表页先展示栏目，打开详情后再用真实的信息来源覆盖。 */
enum class NewsSection(val label: String) {
    NOTICE("通知公告"),
    HEADLINES("要闻速递"),
    RESEARCH("教学科研"),
    CAMPUS("基层动态"),
    ACADEMIC("学术预告"),
}

/** 规则分类，不声称是 AI 推断。 */
enum class NewsCategory(val label: String) {
    ALL("全部"),
    COMPETITION("竞赛"),
    FUNDING("奖助"),
    POSTGRADUATE("保研"),
    LECTURE("讲座"),
    CAREER("就业"),
    RESEARCH("科研"),
    ACTIVITY("活动"),
    OTHER("其他"),
}

data class NewsArticle(
    /** 来自官网 /info/{section}/{id}.htm 的稳定标识，同时用于缓存去重。 */
    val id: String,
    val title: String,
    val url: String,
    /** ISO 日期 yyyy-MM-dd；学术预告等活动日期另存于 eventDate。 */
    val publishedAt: String,
    val section: NewsSection,
    val publisher: String = "",
    val excerpt: String = "",
    val body: String = "",
    val category: NewsCategory = NewsCategory.OTHER,
    /** 0..100 的可解释规则匹配分；未设置画像时仍按时效给基础分。 */
    val matchScore: Int = 0,
    val matchReasons: List<String> = emptyList(),
    val keyPoints: List<String> = emptyList(),
    val eventDate: String? = null,
    val eventPlace: String? = null,
)

data class NewsDigest(
    val excerpt: String,
    val keyPoints: List<String>,
    val eventDate: String? = null,
    val eventPlace: String? = null,
)

/** 第一版只保存兴趣词；院系/专业以后可从 CAS 画像补入。 */
data class NewsProfile(
    val interests: List<String> = DEFAULT_INTERESTS,
) {
    companion object {
        val DEFAULT_INTERESTS = listOf("学科竞赛", "人工智能", "创新创业")
        val SUGGESTED_INTERESTS = listOf(
            "学科竞赛", "人工智能", "创新创业", "奖学金", "保研", "学术讲座", "就业实习", "体育运动",
        )
    }
}
