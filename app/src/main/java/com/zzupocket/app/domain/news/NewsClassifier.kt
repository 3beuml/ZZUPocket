package com.zzupocket.app.domain.news

/** 基于显式关键词的可解释分类；规则顺序就是冲突时的优先级。 */
object NewsClassifier {
    fun classify(title: String, body: String = ""): NewsCategory {
        val text = "$title\n$body"
        return when {
            containsAny(text, COMPETITION) -> NewsCategory.COMPETITION
            containsAny(text, FUNDING) -> NewsCategory.FUNDING
            containsAny(text, POSTGRADUATE) ||
                (text.contains("夏令营") && containsAny(text, GRADUATE_ADMISSION)) -> NewsCategory.POSTGRADUATE
            containsAny(text, LECTURE) -> NewsCategory.LECTURE
            containsAny(text, CAREER) -> NewsCategory.CAREER
            containsAny(text, RESEARCH) -> NewsCategory.RESEARCH
            containsAny(text, ACTIVITY) -> NewsCategory.ACTIVITY
            else -> NewsCategory.OTHER
        }
    }

    private fun containsAny(text: String, words: List<String>) = words.any(text::contains)

    private val COMPETITION = listOf("挑战杯", "竞赛", "大赛", "比赛", "选拔赛")
    private val FUNDING = listOf("奖学金", "助学金", "困难补助", "评奖评优", "资助", "奖助")
    private val POSTGRADUATE = listOf("推免", "免试攻读", "保研")
    private val GRADUATE_ADMISSION = listOf("研究生招生", "招收研究生", "研究生", "硕士", "博士")
    private val LECTURE = listOf("讲座", "报告会", "学术预告", "大讲堂", "讲坛")
    private val CAREER = listOf("招聘", "就业", "实习", "双选会", "宣讲会")
    private val RESEARCH = listOf("科研", "论文", "研究取得", "学术成果")
    private val ACTIVITY = listOf("活动", "开放日", "文化节", "征集", "报名")
}
