package com.zzupocket.app.domain.news

import org.junit.Assert.assertEquals
import org.junit.Test

class NewsClassifierTest {
    @Test
    fun `七类规则均可识别`() {
        assertEquals(NewsCategory.COMPETITION, NewsClassifier.classify("关于挑战杯选拔赛的通知"))
        assertEquals(NewsCategory.FUNDING, NewsClassifier.classify("国家奖学金评奖评优通知"))
        assertEquals(NewsCategory.POSTGRADUATE, NewsClassifier.classify("优秀本科生推免免试攻读研究生"))
        assertEquals(NewsCategory.LECTURE, NewsClassifier.classify("学术预告：名师大讲堂"))
        assertEquals(NewsCategory.CAREER, NewsClassifier.classify("春季双选会暨就业实习招聘"))
        assertEquals(NewsCategory.RESEARCH, NewsClassifier.classify("我校科研团队论文研究取得新进展"))
        assertEquals(NewsCategory.ACTIVITY, NewsClassifier.classify("校园文化节活动报名"))
        assertEquals(NewsCategory.OTHER, NewsClassifier.classify("校园道路维护通告"))
    }

    @Test
    fun `竞赛通知中的报名不会误归活动`() {
        assertEquals(NewsCategory.COMPETITION, NewsClassifier.classify("程序设计大赛", "请各队于周五前报名参赛"))
    }

    @Test
    fun `夏令营只有涉及研究生招生才归保研`() {
        assertEquals(NewsCategory.POSTGRADUATE, NewsClassifier.classify("研究生招生夏令营通知"))
        assertEquals(NewsCategory.OTHER, NewsClassifier.classify("中学生校园夏令营安排"))
    }
}
