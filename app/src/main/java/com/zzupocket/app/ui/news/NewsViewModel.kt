package com.zzupocket.app.ui.news

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.ai.AiPrompts
import com.zzupocket.app.data.ai.AiProviderConfig
import com.zzupocket.app.data.ai.AiSummaryClient
import com.zzupocket.app.data.news.NewsRefreshMode
import com.zzupocket.app.domain.news.NewsArticle
import com.zzupocket.app.domain.news.NewsCategory
import com.zzupocket.app.domain.news.NewsClassifier
import com.zzupocket.app.domain.news.NewsDigestExtractor
import com.zzupocket.app.domain.news.NewsMatcher
import com.zzupocket.app.domain.news.NewsProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NewsAiDetailUi(
    val text: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val model: String = "",
    /** 地址 + 模型 + 正文指纹；任一变化都不能复用旧摘要 */
    val sourceKey: String = "",
)

data class NewsAiOverviewUi(
    val text: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val model: String = "",
)

/** 把公开网页、兴趣偏好与本地规则接成页面状态；正文按需加载并回到列表缓存。 */
class NewsViewModel : ViewModel() {
    private val repository = AppGraph.newsRepository
    private val prefs = AppGraph.newsPrefsStore
    private val aiStore = AppGraph.aiProviderStore
    private val aiClient = AiSummaryClient()
    private val selectedCategory = MutableStateFlow(NewsCategory.ALL)

    private val _aiDetails = MutableStateFlow<Map<String, NewsAiDetailUi>>(emptyMap())
    val aiDetails: StateFlow<Map<String, NewsAiDetailUi>> = _aiDetails.asStateFlow()

    private val _aiOverview = MutableStateFlow(NewsAiOverviewUi())
    val aiOverview: StateFlow<NewsAiOverviewUi> = _aiOverview.asStateFlow()

    val aiConfigured = aiStore.config
        .map { it.configured }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            aiStore.config.value.configured,
        )

    private val base = combine(
        repository.articles,
        repository.refreshing,
        repository.lastError,
        repository.lastUpdated,
    ) { articles, refreshing, error, updated ->
        BaseState(articles, refreshing, error, updated)
    }

    val state = combine(
        base,
        selectedCategory,
        prefs.profile,
        repository.cacheState,
    ) { base, category, profile, cache ->
        val enriched = base.articles.map { article ->
            val classified = if (article.category == NewsCategory.OTHER) {
                article.copy(category = NewsClassifier.classify(article.title, article.body))
            } else article
            // 列表阶段没有正文时，用列表摘要参与匹配；否则只有标题命中，
            // "AI 相关新闻"这类正文信息会全部漏掉。
            val scoring = if (classified.body.isBlank() && classified.excerpt.isNotBlank()) {
                classified.copy(body = classified.excerpt)
            } else {
                classified
            }
            val match = NewsMatcher.match(scoring, profile)
            classified.copy(matchScore = match.matchScore, matchReasons = match.matchReasons)
        }.sortedWith(
            compareByDescending<NewsArticle> { it.matchScore }
                .thenByDescending { it.publishedAt }
                .thenByDescending { it.id },
        )
        NewsUiState(
            articles = if (category == NewsCategory.ALL) enriched else enriched.filter { it.category == category },
            refreshing = base.refreshing,
            error = base.error,
            lastUpdated = base.updated,
            selectedCategory = category,
            interests = profile.interests,
            pendingCount = cache.pendingCount,
            usingCache = cache.usingCache,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        NewsUiState(refreshing = true),
    )

    init {
        viewModelScope.launch {
            // 先出缓存，再后台拉最新；首次安装没有缓存时就是正常的 loading。
            repository.loadCached()
            repository.refresh(NewsRefreshMode.AUTO)
            prefetchRecent(PREFETCH_LIMIT)
        }
    }

    fun selectCategory(category: NewsCategory) {
        selectedCategory.value = category
    }

    fun toggleInterest(interest: String) {
        viewModelScope.launch {
            val current = state.value.interests
            val next = if (interest in current) current - interest else current + interest
            prefs.setInterests(next)
        }
    }

    fun refresh() {
        if (repository.refreshing.value) return
        viewModelScope.launch {
            // 用户主动刷新：直接应用，不弹「有新新闻」。
            repository.refresh(NewsRefreshMode.MANUAL)
            prefetchRecent(PREFETCH_LIMIT)
        }
    }

    /** 用户点「有新新闻」：应用后台已经拉好的列表。 */
    fun applyPendingNews() {
        viewModelScope.launch {
            repository.applyPending()
            prefetchRecent(PREFETCH_LIMIT)
        }
    }

    fun resetFilters() {
        selectedCategory.value = NewsCategory.ALL
        viewModelScope.launch { prefs.setInterests(NewsProfile.DEFAULT_INTERESTS) }
    }

    suspend fun detail(article: NewsArticle): NewsArticle = enrich(article)

    /** 用户点「AI 提炼」才调用；同一文章+模型已有结果时直接复用。 */
    fun generateAiDetail(article: NewsArticle, force: Boolean = false) {
        val config = aiStore.config.value
        val body = article.body.ifBlank { article.excerpt }
        val sourceKey = aiSourceKey(config, body)
        val cached = _aiDetails.value[article.id]
        if (!force && cached?.text != null && cached.sourceKey == sourceKey) return
        if (_aiDetails.value[article.id]?.loading == true) return

        if (!config.configured) {
            _aiDetails.value = _aiDetails.value + (
                article.id to NewsAiDetailUi(
                    error = "请先在「更多 → AI 提供商」配置 API Key",
                    sourceKey = sourceKey,
                )
                )
            return
        }
        if (body.isBlank()) {
            _aiDetails.value = _aiDetails.value + (
                article.id to NewsAiDetailUi(
                    error = "正文为空，暂时无法生成摘要，可打开官网原文",
                    sourceKey = sourceKey,
                )
                )
            return
        }

        _aiDetails.value = _aiDetails.value + (
            article.id to NewsAiDetailUi(
                loading = true,
                model = config.effectiveModel,
                sourceKey = sourceKey,
            )
            )
        viewModelScope.launch {
            try {
                val text = aiClient.summarize(
                    config = config,
                    title = article.title,
                    body = body,
                    publisher = article.publisher,
                    publishedAt = article.publishedAt,
                )
                _aiDetails.value = _aiDetails.value + (
                    article.id to NewsAiDetailUi(
                        text = text,
                        model = config.effectiveModel,
                        sourceKey = sourceKey,
                    )
                    )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _aiDetails.value = _aiDetails.value + (
                    article.id to NewsAiDetailUi(
                        error = e.message ?: "AI 摘要生成失败",
                        model = config.effectiveModel,
                        sourceKey = sourceKey,
                    )
                    )
            }
        }
    }

    private fun aiSourceKey(config: AiProviderConfig, body: String): String =
        "${config.effectiveBaseUrl}|${config.effectiveModel}|${body.hashCode()}"

    /** 雷达顶部「AI 速览」：只发前 5 条的标题和摘要，不传全文。 */
    fun generateAiOverview(articles: List<NewsArticle>) {
        if (_aiOverview.value.loading) return
        val config = aiStore.config.value
        if (!config.configured) {
            _aiOverview.value = NewsAiOverviewUi(error = "请先在「更多 → AI 提供商」配置 API Key")
            return
        }
        val items = articles.take(5).map {
            AiPrompts.OverviewItem(
                title = it.title,
                publisher = it.publisher,
                publishedAt = it.publishedAt,
                excerpt = it.excerpt,
            )
        }
        if (items.isEmpty()) {
            _aiOverview.value = NewsAiOverviewUi(error = "当前分类还没有可速览的资讯")
            return
        }

        _aiOverview.value = NewsAiOverviewUi(loading = true, model = config.effectiveModel)
        viewModelScope.launch {
            try {
                val text = aiClient.overview(config, items)
                _aiOverview.value = NewsAiOverviewUi(text = text, model = config.effectiveModel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _aiOverview.value = NewsAiOverviewUi(
                    error = e.message ?: "AI 速览生成失败",
                    model = config.effectiveModel,
                )
            }
        }
    }

    fun clearAiOverview() {
        _aiOverview.value = NewsAiOverviewUi()
    }

    private suspend fun prefetchRecent(limit: Int) {
        val targets = repository.articles.value
            .sortedByDescending { it.publishedAt }
            .filter { it.excerpt.isBlank() }
            .take(limit)
        targets.forEach { article ->
            try {
                enrich(article)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // 单篇补摘要失败不影响列表；用户点开时还会再试一次。
            }
        }
    }

    private suspend fun enrich(article: NewsArticle): NewsArticle {
        val detailed = repository.detail(article)
        val digest = NewsDigestExtractor.extract(detailed.body)
        val enriched = detailed.copy(
            excerpt = detailed.excerpt.ifBlank { digest.excerpt },
            category = NewsClassifier.classify(detailed.title, detailed.body),
            keyPoints = digest.keyPoints,
            eventDate = detailed.eventDate ?: digest.eventDate,
            eventPlace = detailed.eventPlace ?: digest.eventPlace,
        )
        repository.update(enriched)
        return enriched
    }

    private data class BaseState(
        val articles: List<NewsArticle>,
        val refreshing: Boolean,
        val error: String?,
        val updated: Long?,
    )

    private companion object {
        const val PREFETCH_LIMIT = 6
    }
}
