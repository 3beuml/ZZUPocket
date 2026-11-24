package com.zzupocket.app.ui.news

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.ai.AiPrompts
import com.zzupocket.app.data.ai.AiProviderConfig
import com.zzupocket.app.data.ai.AiSummaryClient
import com.zzupocket.app.data.news.NewsRefreshMode
import com.zzupocket.app.data.news.NewsRequestPriority
import com.zzupocket.app.domain.news.NewsArticle
import com.zzupocket.app.domain.news.NewsCategory
import com.zzupocket.app.domain.news.NewsClassifier
import com.zzupocket.app.domain.news.NewsDigestExtractor
import com.zzupocket.app.domain.news.NewsFeedFilter
import com.zzupocket.app.domain.news.NewsFeedPolicy
import com.zzupocket.app.domain.news.NewsSortMode
import com.zzupocket.app.domain.news.NewsTopic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

/** 预取只补列表里没有摘要的最近几条，且数量有限；用户点开详情时整批取消。 */
internal fun prefetchTargets(articles: List<NewsArticle>, limit: Int): List<NewsArticle> =
    articles.sortedByDescending { it.publishedAt }
        .filter { it.excerpt.isBlank() }
        .take(limit)

/** 把公开网页、兴趣偏好与本地规则接成页面状态；正文按需加载并回到列表缓存。 */
class NewsViewModel : ViewModel() {
    private val repository = AppGraph.newsRepository
    private val prefs = AppGraph.newsPrefsStore
    private val aiStore = AppGraph.aiProviderStore
    private val aiClient = AiSummaryClient()
    private val filterOverride = MutableStateFlow<NewsFeedFilter?>(null)

    /** 后台预取任务；用户点开任一详情时取消，避免正文排队。 */
    private var prefetchJob: Job? = null

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

    private val filter = combine(prefs.filter, filterOverride) { saved, local -> local ?: saved }

    val state = combine(
        base,
        filter,
        repository.cacheState,
    ) { base, filter, cache ->
        NewsUiState(
            articles = NewsFeedPolicy.build(base.articles, filter),
            refreshing = base.refreshing,
            error = base.error,
            lastUpdated = base.updated,
            filter = filter,
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
            schedulePrefetch()
        }
    }

    fun toggleTopic(topic: NewsTopic) {
        val current = filterOverride.value ?: state.value.filter
        filterOverride.value = current.toggleTopic(topic)
        viewModelScope.launch {
            prefs.updateFilter { saved -> saved.toggleTopic(topic) }
            filterOverride.value = null
        }
    }

    fun setOnlyFollowed(onlyFollowed: Boolean) {
        val current = filterOverride.value ?: state.value.filter
        filterOverride.value = current.copy(onlyFollowed = onlyFollowed)
        viewModelScope.launch {
            prefs.updateFilter { it.copy(onlyFollowed = onlyFollowed) }
            filterOverride.value = null
        }
    }

    fun setSortMode(sortMode: NewsSortMode) {
        val current = filterOverride.value ?: state.value.filter
        filterOverride.value = current.withSortMode(sortMode)
        viewModelScope.launch {
            prefs.updateFilter { it.withSortMode(sortMode) }
            filterOverride.value = null
        }
    }

    fun refresh() {
        if (repository.refreshing.value) return
        viewModelScope.launch {
            // 用户主动刷新：直接应用，不弹「有新新闻」。
            repository.refresh(NewsRefreshMode.MANUAL)
            schedulePrefetch()
        }
    }

    /** 用户点「有新新闻」：应用后台已经拉好的列表。 */
    fun applyPendingNews() {
        viewModelScope.launch {
            repository.applyPending()
            schedulePrefetch()
        }
    }

    fun resetFilters() {
        filterOverride.value = NewsFeedFilter()
        viewModelScope.launch {
            prefs.setFilter(NewsFeedFilter())
            filterOverride.value = null
        }
    }

    fun clearFilters() {
        val current = filterOverride.value ?: state.value.filter
        filterOverride.value = current.copy(topics = emptySet(), onlyFollowed = false)
        viewModelScope.launch {
            prefs.setFilter(NewsFeedFilter(topics = emptySet()))
            filterOverride.value = null
        }
    }

    /**
     * 用户点开详情：先取消后台预取，再走用户通道。
     *
     * 预取是串行且受 1 秒限速的；不取消的话，点开一篇要排在剩余预取后面。
     */
    suspend fun detail(article: NewsArticle): NewsArticle {
        prefetchJob?.cancel()
        prefetchJob = null
        return enrich(article, NewsRequestPriority.USER)
    }

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
            _aiOverview.value = NewsAiOverviewUi(error = "当前筛选下还没有可速览的资讯")
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

    /**
     * 进列表后先让用户看几秒，再补最近两条没有摘要的正文。
     *
     * 延迟是为了避开「刚进页面就点第一条」的窗口；数量从 6 降到 2，
     * 剩下的用户点开时走用户通道，不再预先占满限速队列。
     */
    private fun schedulePrefetch() {
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            delay(PREFETCH_DELAY_MS)
            prefetchRecent(PREFETCH_LIMIT)
        }
    }

    private suspend fun prefetchRecent(limit: Int) {
        prefetchTargets(repository.articles.value, limit).forEach { article ->
            try {
                enrich(article, NewsRequestPriority.BACKGROUND)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 单篇补摘要失败不影响列表；用户点开时还会再试一次。
            }
        }
    }

    private suspend fun enrich(
        article: NewsArticle,
        priority: NewsRequestPriority = NewsRequestPriority.USER,
    ): NewsArticle {
        val detailed = repository.detail(article, priority)
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
        /** 首屏最多补两条正文；更多靠用户点开时按需加载。 */
        const val PREFETCH_LIMIT = 2

        /** 等列表稳定、用户开始阅读后再预取，避开刚进页面就点击的窗口。 */
        const val PREFETCH_DELAY_MS = 3_000L
    }
}
