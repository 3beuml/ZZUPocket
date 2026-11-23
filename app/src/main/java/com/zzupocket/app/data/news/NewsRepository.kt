package com.zzupocket.app.data.news

import android.util.Log
import androidx.room.withTransaction
import com.zzupocket.app.data.db.AppDatabase
import com.zzupocket.app.data.db.NewsArticleEntity
import com.zzupocket.app.domain.news.NewsArticle
import com.zzupocket.app.domain.news.NewsCategory
import com.zzupocket.app.domain.news.NewsSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

enum class NewsRefreshMode { AUTO, MANUAL }

data class NewsRefreshResult(
    val applied: Boolean,
    val newCount: Int,
)

/** 缓存展示状态：是否正在看旧快照、有多少条待应用的新资讯。 */
data class NewsCacheState(
    val pendingCount: Int = 0,
    val usingCache: Boolean = false,
)

private val newsCacheJson = Json { ignoreUnknownKeys = true }

/** 后台刷新与当前已显示列表比较，返回用户还没见过的文章 id（保持新列表顺序）。 */
internal fun newArticleIds(previous: List<NewsArticle>, fresh: List<NewsArticle>): List<String> {
    if (previous.isEmpty()) return fresh.map { it.id }
    val known = previous.mapTo(HashSet()) { it.id }
    return fresh.mapNotNull { article -> article.id.takeIf { it !in known } }
}

/** 资讯 → Room 行。keyPoints 用 JSON 存，避免再建一张子表。 */
internal fun NewsArticle.toNewsEntity(fetchedAt: Long): NewsArticleEntity = NewsArticleEntity(
    id = id,
    title = title,
    url = url,
    publishedAt = publishedAt,
    section = section.name,
    publisher = publisher,
    excerpt = excerpt,
    body = body,
    category = category.name,
    keyPoints = if (keyPoints.isEmpty()) {
        ""
    } else {
        newsCacheJson.encodeToString(ListSerializer(String.serializer()), keyPoints)
    },
    eventDate = eventDate,
    eventPlace = eventPlace,
    fetchedAt = fetchedAt,
)

/** Room 行 → 资讯。枚举名对不上（未来删了某个栏目）时退回默认值，不让缓存整张作废。 */
internal fun NewsArticleEntity.toNewsArticle(): NewsArticle = NewsArticle(
    id = id,
    title = title,
    url = url,
    publishedAt = publishedAt,
    section = NewsSection.entries.firstOrNull { it.name == section } ?: NewsSection.NOTICE,
    publisher = publisher,
    excerpt = excerpt,
    body = body,
    category = NewsCategory.entries.firstOrNull { it.name == category } ?: NewsCategory.OTHER,
    keyPoints = if (keyPoints.isBlank()) {
        emptyList()
    } else {
        runCatching {
            newsCacheJson.decodeFromString(ListSerializer(String.serializer()), keyPoints)
        }.getOrDefault(emptyList())
    },
    eventDate = eventDate,
    eventPlace = eventPlace,
)

/**
 * 资讯雷达仓库：Room 快照 + 后台刷新 + 待应用的新资讯。
 *
 * 打开页面先读缓存；自动刷新只把新列表存成 pending，等用户点「有新新闻」再应用。
 * 手动刷新（[NewsRefreshMode.MANUAL]）则直接应用并覆盖缓存。
 */
class NewsRepository(
    private val db: AppDatabase,
    private val client: NewsClient = NewsClient(),
) {
    private val dao = db.newsArticleDao()

    private val _articles = MutableStateFlow<List<NewsArticle>>(emptyList())
    val articles: StateFlow<List<NewsArticle>> = _articles.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** 最近一次**已经应用**的刷新时间；有待应用新资讯时仍是缓存时间。 */
    private val _lastUpdated = MutableStateFlow<Long?>(null)
    val lastUpdated: StateFlow<Long?> = _lastUpdated.asStateFlow()

    private val _cacheState = MutableStateFlow(NewsCacheState())
    val cacheState: StateFlow<NewsCacheState> = _cacheState.asStateFlow()

    private var pending: List<NewsArticle>? = null
    private var lastAutoRefreshAt = 0L
    private val refreshMutex = Mutex()

    /** 打开雷达时先调用：有缓存就立刻显示，不等待网络。 */
    suspend fun loadCached(): List<NewsArticle> = withContext(Dispatchers.IO) {
        val rows = dao.all()
        if (rows.isEmpty()) return@withContext emptyList()
        val list = rows.map { it.toNewsArticle() }.sortedWith(
            compareByDescending<NewsArticle> { it.publishedAt }.thenByDescending { it.id },
        )
        _articles.value = list
        _lastUpdated.value = rows.maxOfOrNull { it.fetchedAt }
        _cacheState.update { it.copy(usingCache = true) }
        list
    }

    /**
     * 拉取五个栏目并合并。
     *
     * AUTO 模式下，如果本地已有内容且发现新 id，只存成 pending，等待用户点击；
     * MANUAL 模式、首次无缓存、或没有新 id 时直接应用。
     */
    suspend fun refresh(mode: NewsRefreshMode = NewsRefreshMode.AUTO): NewsRefreshResult =
        refreshMutex.withLock {
            val now = System.currentTimeMillis()
            if (mode == NewsRefreshMode.AUTO && _articles.value.isNotEmpty() &&
                now - lastAutoRefreshAt < AUTO_COOLDOWN_MS
            ) {
                return@withLock NewsRefreshResult(applied = false, newCount = 0)
            }
            if (mode == NewsRefreshMode.AUTO) lastAutoRefreshAt = now

            _refreshing.value = true
            try {
                val failures = mutableListOf<String>()
                val results = coroutineScope {
                    NewsSource.ALL.map { source ->
                        async {
                            try {
                                source.section to client.list(source)
                                    .distinctBy { it.id }
                                    .take(PER_SECTION_LIMIT)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG, "${source.section.label}刷新失败", e)
                                synchronized(failures) {
                                    failures += source.section.label
                                }
                                source.section to null
                            }
                        }
                    }.map { it.await() }
                }

                val successCount = results.count { it.second != null }
                if (successCount == 0) {
                    _lastError.value = "网络不可用，请检查网络后重试"
                    // 有缓存时保留；无缓存时失败也不写空列表。
                    return@withLock NewsRefreshResult(applied = false, newCount = 0)
                }

                val merged = LinkedHashMap<String, NewsArticle>()
                results.forEach { (section, fresh) ->
                    if (fresh == null) {
                        _articles.value.filter { it.section == section }
                            .forEach { merged.putIfAbsent(it.id, it) }
                    } else {
                        fresh.forEach { merged.putIfAbsent(it.id, it) }
                    }
                }
                val freshList = merged.values.sortedWith(
                    compareByDescending<NewsArticle> { it.publishedAt }.thenByDescending { it.id },
                )
                if (freshList.isEmpty()) {
                    _lastError.value = if (failures.isEmpty()) {
                        "没有读到资讯"
                    } else {
                        failures.joinToString("、", prefix = "部分栏目暂未更新：")
                    }
                    return@withLock NewsRefreshResult(applied = false, newCount = 0)
                }

                _lastError.value = failures.takeIf { it.isNotEmpty() }
                    ?.joinToString("、", prefix = "部分栏目暂未更新：")
                val newCount = newArticleIds(_articles.value, freshList).size
                val shouldWaitForUser = mode == NewsRefreshMode.AUTO &&
                    _articles.value.isNotEmpty() &&
                    newCount > 0

                if (shouldWaitForUser) {
                    pending = freshList
                    _cacheState.update { it.copy(pendingCount = newCount, usingCache = true) }
                    NewsRefreshResult(applied = false, newCount = newCount)
                } else {
                    applyList(freshList)
                    NewsRefreshResult(applied = true, newCount = newCount)
                }
            } finally {
                _refreshing.value = false
            }
        }

    /** 用户点「有新新闻」：应用已经拉好的 pending，不再请求网络。 */
    suspend fun applyPending(): Boolean {
        val next = pending ?: return false
        applyList(next)
        return true
    }

    /** 补齐真实信息来源、正文及摘要；学校未提供摘要时取正文首段。 */
    suspend fun detail(article: NewsArticle): NewsArticle {
        val parsed = client.detail(article.url)
        val detailed = article.copy(
            title = parsed.title.ifBlank { article.title },
            // 列表可能已有摘要/来源/正文；详情页缺字段时不要用空值把已有内容冲掉。
            publisher = parsed.publisher.ifBlank { article.publisher },
            excerpt = parsed.excerpt.ifBlank { article.excerpt },
            body = parsed.body.ifBlank { article.body },
            publishedAt = parsed.publishedAt.ifBlank { article.publishedAt },
            eventDate = article.eventDate ?: parsed.eventDate,
            eventPlace = article.eventPlace ?: parsed.eventPlace,
        )
        update(detailed)
        return detailed
    }

    /** 回填同 id 条目（详情的分类/摘要/要点），并同步更新缓存。 */
    suspend fun update(article: NewsArticle) {
        _articles.value = _articles.value.map { if (it.id == article.id) article else it }
        pending = pending?.map { if (it.id == article.id) article else it }
        // 用当前快照时间而不是 now：单独补一篇详情不该让「更新于」看起来刚刷新过。
        val fetchedAt = _lastUpdated.value ?: System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            dao.upsert(article.toNewsEntity(fetchedAt))
        }
    }

    private suspend fun applyList(fresh: List<NewsArticle>) = withContext(Dispatchers.IO) {
        val cached = _articles.value.associateBy { it.id }
        val pendingMap = pending?.associateBy { it.id }.orEmpty()
        val merged = fresh.map { item ->
            val old = cached[item.id] ?: pendingMap[item.id]
            if (old == null) {
                item
            } else {
                item.copy(
                    publisher = item.publisher.ifBlank { old.publisher },
                    excerpt = item.excerpt.ifBlank { old.excerpt },
                    body = item.body.ifBlank { old.body },
                    category = if (item.category == NewsCategory.OTHER) old.category else item.category,
                    keyPoints = item.keyPoints.ifEmpty { old.keyPoints },
                    eventDate = item.eventDate ?: old.eventDate,
                    eventPlace = item.eventPlace ?: old.eventPlace,
                )
            }
        }.sortedWith(
            compareByDescending<NewsArticle> { it.publishedAt }.thenByDescending { it.id },
        ).take(MAX_CACHE_ITEMS)

        val fetchedAt = System.currentTimeMillis()
        val entities = merged.map { it.toNewsEntity(fetchedAt) }
        db.withTransaction {
            dao.clear()
            dao.upsertAll(entities)
        }
        _articles.value = merged
        _lastUpdated.value = fetchedAt
        pending = null
        _cacheState.value = NewsCacheState(pendingCount = 0, usingCache = false)
    }

    private companion object {
        const val TAG = "NewsRepository"
        const val PER_SECTION_LIMIT = 30
        const val MAX_CACHE_ITEMS = 250
        const val AUTO_COOLDOWN_MS = 60_000L
    }
}
