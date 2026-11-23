package com.zzupocket.app.data.news

import com.zzupocket.app.domain.news.NewsSection

/** 五个公开栏目及各自模板类型。 */
internal data class NewsSource(
    val section: NewsSection,
    val url: String,
    val kind: Kind,
) {
    enum class Kind { NOTICE, NEWS, ACADEMIC }

    companion object {
        val ALL = listOf(
            NewsSource(NewsSection.NOTICE, "https://www.zzu.edu.cn/index/tzgg.htm", Kind.NOTICE),
            NewsSource(NewsSection.HEADLINES, "https://news.zzu.edu.cn/ywsd.htm", Kind.NEWS),
            NewsSource(NewsSection.RESEARCH, "https://news.zzu.edu.cn/jxky.htm", Kind.NEWS),
            NewsSource(NewsSection.CAMPUS, "https://news.zzu.edu.cn/jcdt.htm", Kind.NEWS),
            NewsSource(
                NewsSection.ACADEMIC,
                "https://www.zzu.edu.cn/xsyg.jsp?urltype=tree.TreeTempUrl&wbtreeid=1219",
                Kind.ACADEMIC,
            ),
        )
    }
}
