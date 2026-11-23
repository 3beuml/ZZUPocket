package com.zzupocket.app.data.news

import com.zzupocket.app.domain.news.NewsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学校模板的解析契约。
 *
 * 这里用的是从真实页面裁剪出的最小结构，不联网；学校改版时先改 fixture，
 * 让测试明确指出是哪一段选择器坏了。
 */
class NewsParserTest {
    private val parser = NewsParser()

    @Test
    fun `通知公告列表解析相对链接和日期`() {
        val html = """
            <ul class="notice-box">
              <li class="notice-item">
                <a class="notice-link" href="../info/1442/89408.htm">
                  <div class="notice-link-text">郑州大学物理学院关于举办第十届科普开放日活动的公告</div>
                  <div class="notice-link-time">2026-11-13</div>
                </a>
              </li>
            </ul>
        """.trimIndent()

        val articles = parser.noticeList(html, "https://www.zzu.edu.cn/index/tzgg.htm")

        assertEquals(1, articles.size)
        assertEquals("1442/89408", articles.single().id)
        assertEquals("https://www.zzu.edu.cn/info/1442/89408.htm", articles.single().url)
        assertEquals("2026-11-13", articles.single().publishedAt)
        assertEquals(NewsSection.NOTICE, articles.single().section)
    }

    @Test
    fun `新闻网列表保留列表自带摘要`() {
        val html = """
            <div class="new-item">
              <div class="new-center">
                <h3><a href="http://www.zzu.edu.cn/info/1217/89471.htm" title="新闻标题">新闻标题</a></h3>
                <p><a href="http://www.zzu.edu.cn/info/1217/89471.htm">这是列表给出的摘要。</a></p>
              </div>
              <div class="new-date">2026-11-23</div>
            </div>
        """.trimIndent()

        val articles = parser.newsList(html, "https://news.zzu.edu.cn/ywsd.htm", NewsSection.HEADLINES)

        assertEquals(1, articles.size)
        assertEquals("新闻标题", articles.single().title)
        assertEquals("这是列表给出的摘要。", articles.single().excerpt)
        assertEquals("2026-11-23", articles.single().publishedAt)
    }

    @Test
    fun `学术预告把活动时间和地点单独保留`() {
        val html = """
            <li class="xsyg-item">
              <a href="../info/1219/89143.htm">
                <div class="xsyg-item-info">
                  <p class="xsyg-item-info-title">李忠明教授做客我校研究生名师名家讲坛学术预告</p>
                  <div class="xsyg-item-address">活动地点：主校区综合管理中心第四会议室</div>
                  <div class="xsyg-item-time">活动时间：2026-10-15</div>
                </div>
              </a>
            </li>
        """.trimIndent()

        val articles = parser.academicList(
            html,
            "https://www.zzu.edu.cn/xsyg.jsp?urltype=tree.TreeTempUrl&wbtreeid=1219",
        )

        assertEquals(1, articles.size)
        assertEquals("2026-10-15", articles.single().eventDate)
        assertEquals("主校区综合管理中心第四会议室", articles.single().eventPlace)
        assertEquals("", articles.single().publishedAt)
    }

    @Test
    fun `新闻网列表过滤纯 SEO 关键词摘要`() {
        val html = """
            <div class="new-item">
              <div class="new-center">
                <h3><a href="http://www.zzu.edu.cn/info/1217/89461.htm" title="新闻标题">新闻标题</a></h3>
                <p><a href="http://www.zzu.edu.cn/info/1217/89461.htm">郑州大学, Zhengzhou University,郑大, ZZU</a></p>
              </div>
              <div class="new-date">2026-11-23</div>
            </div>
        """.trimIndent()

        val articles = parser.newsList(html, "https://news.zzu.edu.cn/ywsd.htm", NewsSection.HEADLINES)

        assertEquals(1, articles.size)
        assertEquals("", articles.single().excerpt)
    }

    @Test
    fun `详情页 SEO 关键词摘要退回正文第一段`() {
        val html = """
            <meta name="description" content="郑州大学, Zhengzhou University,郑大, ZZU">
            <h3 class="notice-detail-title">关于举办摄影比赛的通知</h3>
            <div id="vsb_content_4">
              <div class="v_news_content">
                <p>各学院、各单位：</p>
                <p>为丰富校园文化生活，学校决定举办摄影比赛，现将有关事项通知如下。</p>
              </div>
            </div>
        """.trimIndent()

        val detail = parser.detail(html, "https://www.zzu.edu.cn/info/1442/89356.htm")

        assertTrue(detail.excerpt.startsWith("为丰富校园文化生活"))
    }

    @Test
    fun `详情页单行字段摘要退回正文第一段`() {
        val html = """
            <meta name="description" content="主办单位：中国文物报社、对外经济贸易大学">
            <h3 class="notice-detail-title">关于举办文博活动的通知</h3>
            <div id="vsb_content_4">
              <div class="v_news_content">
                <p>为贯彻落实文化强国战略任务，推动中华优秀传统文化创造性转化与创新性发展，现举办文博活动，现将有关事项通知如下：</p>
              </div>
            </div>
        """.trimIndent()

        val detail = parser.detail(html, "https://www.zzu.edu.cn/info/1442/89177.htm")

        assertTrue(detail.excerpt.startsWith("为贯彻落实文化强国战略任务"))
    }

    @Test
    fun `详情页提取标题来源日期和分段正文`() {
        val html = """
            <meta name="description" content="这是官网摘要。">
            <h3 class="notice-detail-title">关于举办活动的公告</h3>
            <span class="notice-detail-info-text">发布时间：2026年11月13日</span>
            <span class="notice-detail-info-text">信息来源：物理学院</span>
            <div id="vsb_content_4">
              <div class="v_news_content">
                <p>第一段正文。</p>
                <p>第二段正文。</p>
              </div>
            </div>
        """.trimIndent()

        val detail = parser.detail(html, "https://www.zzu.edu.cn/info/1442/89408.htm")

        assertEquals("关于举办活动的公告", detail.title)
        assertEquals("物理学院", detail.publisher)
        assertEquals("2026-11-13", detail.publishedAt)
        assertEquals("这是官网摘要。", detail.excerpt)
        assertTrue(detail.body.contains("第一段正文"))
        assertTrue(detail.body.contains("第二段正文"))
    }
}
