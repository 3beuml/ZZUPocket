package com.zzupocket.app.domain.news

/** 统一列表策略：可选关注优先，只有显式开启严格模式才收窄可见集合。 */
object NewsFeedPolicy {
    fun build(articles: List<NewsArticle>, filter: NewsFeedFilter): List<NewsArticle> = articles.map { article ->
        // 列表摘要也参与分类，避免正文尚未加载时漏掉挑战杯、双选会等通知。
        val classified = article.copy(
            category = NewsClassifier.classify(article.title, listOf(article.excerpt, article.body).filter(String::isNotBlank).joinToString("\n")),
        )
        val scoring = if (classified.body.isBlank() && classified.excerpt.isNotBlank()) {
            classified.copy(body = classified.excerpt)
        } else {
            classified
        }
        val profile = NewsProfile(filter.topics.flatMap { topic ->
            if (topic.aliases.isEmpty()) listOf(topic.label) else topic.aliases
        })
        val match = NewsMatcher.match(scoring, profile)
        val topics = NewsMatcher.matchTopics(
            title = article.title,
            body = article.body,
            excerpt = article.excerpt,
            category = classified.category,
            topics = filter.topics,
        )
        classified.copy(
            matchScore = match.matchScore,
            matchReasons = match.matchReasons,
            matchedTopics = topics,
        )
    }.filter { article ->
        !filter.onlyFollowed || filter.topics.isEmpty() || article.matchedTopics.isNotEmpty()
    }.sortedWith(
        if (filter.sortMode == NewsSortMode.RELEVANCE) {
            compareByDescending<NewsArticle> { it.matchScore }
                .thenByDescending { it.publishedAt }
                .thenByDescending { it.id }
        } else {
            compareByDescending<NewsArticle> { it.publishedAt }
                .thenByDescending { it.id }
        },
    )
}
