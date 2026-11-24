package com.zzupocket.app.domain.news

/** 用户关注的一份主题；分类与兴趣共用稳定 ID 和同一套规则。 */
enum class NewsTopic(
    val label: String,
    val category: NewsCategory? = null,
    internal val aliases: List<String> = emptyList(),
) {
    COMPETITION("竞赛", NewsCategory.COMPETITION, listOf("学科竞赛", "竞赛", "大赛", "比赛", "挑战杯", "选拔赛")),
    FUNDING("奖助", NewsCategory.FUNDING, listOf("奖学金", "助学金", "困难补助", "评奖评优", "资助", "奖助")),
    POSTGRADUATE("保研", NewsCategory.POSTGRADUATE, listOf("保研", "推免", "免试攻读")),
    LECTURE("讲座", NewsCategory.LECTURE, listOf("学术讲座", "讲座", "报告会", "学术预告", "大讲堂", "讲坛")),
    CAREER("就业", NewsCategory.CAREER, listOf("就业实习", "就业", "实习", "招聘", "双选会", "宣讲会")),
    RESEARCH("科研", NewsCategory.RESEARCH, listOf("科研", "论文", "研究取得", "学术成果")),
    ACTIVITY("活动", NewsCategory.ACTIVITY, listOf("活动", "开放日", "文化节", "征集", "报名")),
    AI("人工智能", aliases = listOf("人工智能", "ai")),
    INNOVATION("创新创业", aliases = listOf("创新创业", "创新", "创业")),
    SPORTS("体育运动", aliases = listOf("体育运动", "体育", "运动会", "篮球", "足球"));

    companion object {
        /** 仅迁移旧版本明确保存过的兴趣；新版本只持久化稳定枚举名。 */
        fun fromLegacyInterests(interests: List<String>): Set<NewsTopic> = interests.mapNotNull { interest ->
            val normalized = interest.trim().lowercase().replace(WHITESPACE, "")
            entries.firstOrNull { topic ->
                topic.label.equals(interest.trim(), ignoreCase = true) ||
                    topic.aliases.any { it.lowercase().replace(WHITESPACE, "") == normalized }
            }
        }.toSet()

        private val WHITESPACE = Regex("\\s+")
    }
}

enum class NewsSortMode(val label: String) {
    DATE("按时间"),
    RELEVANCE("关注优先"),
}

data class NewsFeedFilter(
    val topics: Set<NewsTopic> = emptySet(),
    val onlyFollowed: Boolean = false,
    val sortMode: NewsSortMode = NewsSortMode.DATE,
) {
    val isActive: Boolean
        get() = topics.isNotEmpty() || onlyFollowed || sortMode != NewsSortMode.DATE
}
