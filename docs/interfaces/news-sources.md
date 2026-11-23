# 校园资讯雷达：公开网页契约

> 第一版只读取**校级公开网页**，不登录、不带 Cookie、不写入任何校园账号信息。
> 学校模板随时可能改版，本文与实际解析器（`app/src/main/java/com/zzupocket/app/data/news/NewsParser.kt`）必须同步维护。
>
> 最后核对：2026-11-23。

## 一、数据源

| 栏目 | 列表页 | 说明 |
|---|---|---|
| 通知公告 | `https://www.zzu.edu.cn/index/tzgg.htm` | 50 条/页，分页为 `index/tzgg/{page}.htm`；第一版只取第一页 |
| 要闻速递 | `https://news.zzu.edu.cn/ywsd.htm` | 分页为 `ywsd/{page}.htm` |
| 教学科研 | `https://news.zzu.edu.cn/jxky.htm` | 同上 |
| 基层动态 | `https://news.zzu.edu.cn/jcdt.htm` | 同上 |
| 学术预告 | `https://www.zzu.edu.cn/xsyg.jsp?urltype=tree.TreeTempUrl&wbtreeid=1219` | 列表项自带活动时间、地点；没有发布日期 |

所有文章都会落到 `https://www.zzu.edu.cn/info/{频道}/{编号}.htm`。解析器只接受这个域名下的 `/info/` 链接，拒绝跳转到其他站点。

栏目没有 `robots.txt`（404），也没有显式禁止抓取；实现上仍保持：

- 每主机至少 1 秒请求间隔（复用 `HttpClients.RateLimitInterceptor`）；
- 只取列表第一页，详情只在打开单篇或首屏补齐最近 6 条时请求；
- 不保存全文用于再分发，正文仅作本机离线阅读缓存。

## 二、列表选择器

### 通知公告

```html
<ul class="notice-box">
  <li class="notice-item">
    <a class="notice-link" href="../info/1442/89408.htm">
      <div class="notice-link-text">标题</div>
      <div class="notice-link-time">2026-11-13</div>
    </a>
  </li>
</ul>
```

选择器：`ul.notice-box li.notice-item` / `a.notice-link[href]` / `.notice-link-text` / `.notice-link-time`。

### 新闻网

```html
<div class="new-item">
  <div class="new-center">
    <h3><a href="http://www.zzu.edu.cn/info/1217/89471.htm" title="标题">标题</a></h3>
    <p><a href="...">列表摘要</a></p>
  </div>
  <div class="new-date">2026-11-23</div>
</div>
```

选择器：`.new-item` / `.new-center h3 a[title][href]` / `.new-center p a`（摘要）/ `.new-date`。
旧式 `ul.content li a[title] + span` 作为兼容回退。

### 学术预告

```html
<li class="xsyg-item">
  <a href="../info/1219/89143.htm">
    <p class="xsyg-item-info-title">标题</p>
    <div class="xsyg-item-address">活动地点：…</div>
    <div class="xsyg-item-time">活动时间：2026-10-15</div>
  </a>
</li>
```

选择器：`li.xsyg-item` / `.xsyg-item-info-title` / `.xsyg-item-address` / `.xsyg-item-time`。
列表没有发布日期，`publishedAt` 留空，活动日期单独放 `eventDate`，不用活动日期冒充发布时间。

## 三、详情页

```html
<h3 class="notice-detail-title">标题</h3>
<span class="notice-detail-info-text">发布时间：2026年11月13日</span>
<span class="notice-detail-info-text">信息来源：物理学院</span>
<div id="vsb_content_4">
  <div class="v_news_content"><p>正文…</p></div>
</div>
```

- 标题：`.notice-detail-title`（新闻与通知实测共用）
- 来源：`.notice-detail-info-text` 中「信息来源：」后的文本
- 发布时间：同节点中「发布时间：」后的日期
- 正文：`#vsb_content_4 .v_news_content`
- 摘要：优先 `meta[name=description]`，否则正文首段，最多 180 字

正文按 `p/h1/h2/h3/h4/li/tr` 逐段提取并保留换行；图片会被移除，因此**纯图片新闻的正文为空**，详情页会退回官网摘要并提示打开原文。

## 四、解析测试

`app/src/test/java/com/zzupocket/app/data/news/NewsParserTest.kt` 保存了上述结构的最小 fixture：

- 相对链接转绝对 URL；
- 信息来源、发布日期、分段正文；
- 新闻网摘要；
- 学术预告的时间/地点。

学校改版时，先更新 fixture 与选择器，跑 `./gradlew :app:testDebugUnitTest`，能直接指出是列表还是详情解析坏了。

## 五、已知边界

1. 只覆盖校级官网；学院站（`www5/www7/www15`）模板不一，尚未接入，因此「保研」「学院竞赛」的覆盖率有限。
2. 只抓第一页，不做历史翻页。
3. 纯图片文章没有正文文本，只能看摘要或原文。
4. 列表、正文与要点已持久化到 Room（`news_articles`，最多 250 条）：打开先读快照再后台
   刷新，杀进程后仍可离线阅读；刷新失败时保留旧快照，不会用空结果覆盖。
5. 分类与匹配是**本地关键词规则**，界面显示「匹配分」和命中理由，不声称是 AI 判断。
6. 列表 / 详情摘要会过滤官网 meta 里的 SEO 关键词（`郑州大学` / `Zhengzhou University` /
   `郑大` / `ZZU`）；只有「主办单位：…」这类单行字段时退回正文第一段。
7. 本地要点按「标题 + 下一行值」「标签：值」提取，只收完整日期 / 时间、地点、对象、方式、
   电话、邮箱；目录行、落款日期和长流程段落不进入。
