package com.zzupocket.app.ui.news

import android.content.res.Configuration
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zzupocket.app.domain.news.NewsArticle
import com.zzupocket.app.domain.news.NewsCategory
import com.zzupocket.app.domain.news.NewsFeedFilter
import com.zzupocket.app.domain.news.NewsSection
import com.zzupocket.app.domain.news.NewsSortMode
import com.zzupocket.app.domain.news.NewsTopic
import com.zzupocket.app.ui.components.GlassCard
import com.zzupocket.app.ui.components.RelativeTime
import com.zzupocket.app.ui.nav.DOCK_CLEARANCE
import com.zzupocket.app.ui.theme.ZZUPocketTheme

/** 列表页只依赖这份不可变快照，可直接由 StateFlow 收集后传入。 */
data class NewsUiState(
    val articles: List<NewsArticle> = emptyList(),
    val refreshing: Boolean = false,
    val error: String? = null,
    val lastUpdated: Long? = null,
    val filter: NewsFeedFilter = NewsFeedFilter(),
    /** 后台已经拉到、但用户还没点「有新新闻」的条数 */
    val pendingCount: Int = 0,
    /** 当前列表是否来自上次缓存 */
    val usingCache: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsScreen(
    state: NewsUiState,
    onToggleTopic: (NewsTopic) -> Unit,
    onOnlyFollowedChange: (Boolean) -> Unit,
    onSortModeChange: (NewsSortMode) -> Unit,
    onRefresh: () -> Unit,
    onApplyPending: () -> Unit,
    onOpenArticle: (NewsArticle) -> Unit,
    onOpenOriginal: (NewsArticle) -> Unit,
    /** null = 底栏 tab 模式，不显示返回箭头 */
    onBack: (() -> Unit)? = null,
    onResetFilters: () -> Unit = {},
    onClearFilters: () -> Unit = {},
    aiOverview: NewsAiOverviewUi = NewsAiOverviewUi(),
    onAiOverview: () -> Unit = {},
    onDismissAiOverview: () -> Unit = {},
    isTab: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var showFilters by remember { mutableStateOf(false) }
    // 用户点「有新新闻」后，等新列表真正落位再滚到顶部；先滚会让旧列表锚点
    // 把顶部新卡片顶到屏幕外。
    var scrollToTopWhenApplied by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 首屏（缓存或第一次拉取）不播放入场动画；等第一个列表落位一帧后再开启，
    // 之后的刷新、点「有新新闻」才有淡入/上移。
    var animateItems by remember { mutableStateOf(false) }
    LaunchedEffect(state.articles.isNotEmpty()) {
        if (state.articles.isNotEmpty() && !animateItems) {
            withFrameNanos { }
            animateItems = true
        }
    }

    // 弹层退出动画期间 pendingCount 已经归零，用最后一次非零值保持文案不闪。
    var lastPendingCount by remember { mutableIntStateOf(0) }
    SideEffect {
        if (state.pendingCount > 0) lastPendingCount = state.pendingCount
    }

    LaunchedEffect(state.pendingCount) {
        if (scrollToTopWhenApplied && state.pendingCount == 0) {
            // 等两帧：第一帧让新列表完成组合与 measure，第二帧再滚；
            // 立刻滚会被 LazyColumn「保持当前首项」的锚定逻辑抵消（新卡片藏在上面）。
            // 注意 flag 必须最后复位：它参与重组，提前复位会取消这个滚动协程。
            withFrameNanos { }
            withFrameNanos { }
            listState.animateScrollToItem(0)
            scrollToTopWhenApplied = false
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("资讯雷达")
                        Text(
                            text = "校园官网 · 公开资讯",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    TextButton(onClick = onAiOverview) {
                        if (aiOverview.loading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("AI 速览")
                        }
                    }
                    IconButton(onClick = onRefresh, enabled = !state.refreshing) {
                        if (state.refreshing) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            NewsFilterBar(
                filterActive = state.filter.isActive,
                lastUpdated = state.lastUpdated,
                refreshing = state.refreshing,
                onOpenFilters = { showFilters = true },
            )
            // 错误提示固定在列表外：如果放在 LazyColumn 顶部，从缓存进入后
            // 列表会把当前首项锚住，提示就被顶到屏幕上方看不见了（真机复现）。
            state.error?.takeIf { it.isNotBlank() }?.let { message ->
                GlassCard(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 4.dp),
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = if (state.articles.isEmpty()) {
                                "资讯加载失败"
                            } else {
                                "暂时无法获取最新资讯"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // tab 模式下内容从悬浮 dock 底下穿过，需要留出尾部余量；
                    // 非 tab（预览或旧入口）按普通全屏页处理。
                    contentPadding = PaddingValues(
                        top = 8.dp,
                        bottom = if (isTab) DOCK_CLEARANCE else 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.articles.isEmpty()) {
                        item {
                            NewsEmptyContent(
                                refreshing = state.refreshing,
                                hasError = !state.error.isNullOrBlank(),
                                filtered = state.filter.onlyFollowed && state.filter.topics.isNotEmpty(),
                                onRefresh = onRefresh,
                                onClearFilters = onClearFilters,
                            )
                        }
                    } else {
                        items(state.articles, key = { it.id }) { article ->
                            NewsArticleCard(
                                article = article,
                                onOpenArticle = { onOpenArticle(article) },
                                onOpenOriginal = { onOpenOriginal(article) },
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    // key + animateItem：新卡片淡入，旧卡片平滑让位。
                                    .then(if (animateItems) Modifier.animateItem() else Modifier),
                            )
                        }
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = state.pendingCount > 0,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp),
                    enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                ) {
                    NewNewsPill(
                        count = lastPendingCount,
                        onClick = {
                            scrollToTopWhenApplied = true
                            onApplyPending()
                        },
                    )
                }
            }
        }
    }

    if (showFilters) {
        NewsFilterSheet(
            filter = state.filter,
            onToggleTopic = onToggleTopic,
            onOnlyFollowedChange = onOnlyFollowedChange,
            onSortModeChange = onSortModeChange,
            onReset = onResetFilters,
            onDismiss = { showFilters = false },
        )
    }

    if (aiOverview.loading || aiOverview.text != null || aiOverview.error != null) {
        ModalBottomSheet(onDismissRequest = onDismissAiOverview) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("AI 速览", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    if (aiOverview.model.isNotBlank()) {
                        Text(
                            aiOverview.model,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                when {
                    aiOverview.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在生成…", style = MaterialTheme.typography.bodySmall)
                    }

                    aiOverview.error != null -> {
                        Text(
                            aiOverview.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = onAiOverview) { Text("重试") }
                    }

                    else -> MarkdownText(
                        markdown = aiOverview.text.orEmpty(),
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "由 ${aiOverview.model.ifBlank { "AI" }} 生成，可能有误；以官网原文为准。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 顶部固定条：只留一个「筛选」入口 + 更新时间，兴趣/分类都收进弹层。 */
@Composable
private fun NewsFilterBar(
    filterActive: Boolean,
    lastUpdated: Long?,
    refreshing: Boolean,
    onOpenFilters: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onOpenFilters,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            colors = if (filterActive) {
                ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            } else {
                ButtonDefaults.outlinedButtonColors()
            },
        ) {
            Icon(
                Icons.Filled.Tune,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("筛选", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.weight(1f))
        val status = when {
            refreshing && lastUpdated != null -> "更新中…"
            lastUpdated != null -> "更新于 ${RelativeTime.ago(lastUpdated)}"
            refreshing -> "加载中…"
            else -> ""
        }
        if (status.isNotEmpty()) {
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 统一关注主题；默认优先推荐，用户显式开启后才隐藏其他资讯。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NewsFilterSheet(
    filter: NewsFeedFilter,
    onToggleTopic: (NewsTopic) -> Unit,
    onOnlyFollowedChange: (Boolean) -> Unit,
    onSortModeChange: (NewsSortMode) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 筛选内容比半屏高：直接全展开，避免用户还要再上滑才看得到开关和「完成」。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("筛选", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onReset) { Text("重置") }
            }
            Text(
                "排序方式",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NewsSortMode.entries.forEach { mode ->
                    FilterChip(
                        selected = filter.sortMode == mode,
                        onClick = { onSortModeChange(mode) },
                        label = { Text(mode.label) },
                        enabled = mode == NewsSortMode.DATE || filter.topics.isNotEmpty(),
                        modifier = Modifier.height(36.dp),
                    )
                }
            }
            Text(
                "关注内容",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "选择你关心的主题，可多选。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NewsTopic.entries.forEach { topic ->
                    FilterChip(
                        selected = topic in filter.topics,
                        onClick = { onToggleTopic(topic) },
                        label = { Text(topic.label) },
                        modifier = Modifier.height(36.dp),
                    )
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("只看关注内容", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (filter.topics.isEmpty()) {
                                "未选主题时显示全部资讯"
                            } else if (filter.onlyFollowed) {
                                "隐藏无关资讯，只显示所选主题"
                            } else {
                                "相关资讯优先，其他资讯仍然保留"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = filter.onlyFollowed, onCheckedChange = onOnlyFollowedChange)
                }
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Text("完成")
            }
        }
    }
}

/** 仿 X 的蓝色小胶囊：浮在列表顶部，点击才应用后台拉好的新列表。 */
@Composable
private fun NewNewsPill(
    count: Int,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.ArrowUpward,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "有新新闻 · $count 条",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun NewsEmptyContent(
    refreshing: Boolean,
    hasError: Boolean,
    filtered: Boolean,
    onRefresh: () -> Unit,
    onClearFilters: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (refreshing) {
            CircularProgressIndicator()
            Text(
                text = "正在读取校园公开资讯…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = if (filtered) "当前关注内容暂无资讯" else "暂无资讯",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = when {
                    hasError -> "请检查网络后重试，或稍后再来查看。"
                    filtered -> "可更换关注主题，或清除筛选查看全部资讯。"
                    else -> "还没有可显示的公开资讯，可点击刷新重试。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (filtered) {
                OutlinedButton(onClick = onClearFilters) { Text("清除筛选") }
            } else {
                OutlinedButton(onClick = onRefresh) { Text("刷新") }
            }
        }
    }
}

@Composable
private fun NewsArticleCard(
    article: NewsArticle,
    onOpenArticle: () -> Unit,
    onOpenOriginal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenArticle).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Text(
                        text = "匹配 ${article.matchScore.coerceIn(0, 100)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = listOf(
                        article.category.label,
                        article.publisher.ifBlank { article.section.label },
                        article.publishedAt,
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = article.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = article.excerpt.ifBlank { "打开查看官网原文与本地要点" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            article.matchReasons.firstOrNull { it.isNotBlank() }?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onOpenArticle) { Text("本地要点") }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onOpenOriginal, enabled = article.url.isNotBlank()) {
                    Text("原文")
                }
            }
        }
    }
}

private val previewArticle = NewsArticle(
    id = "preview",
    title = "关于举办大学生创新竞赛校内选拔赛的通知",
    url = "https://www.zzu.edu.cn/",
    publishedAt = "2026-11-23",
    section = NewsSection.NOTICE,
    publisher = "教务处",
    excerpt = "现将校内选拔赛报名安排、作品要求与评审流程通知如下，请有意参赛的同学及时查看。",
    category = NewsCategory.COMPETITION,
    matchScore = 82,
    matchReasons = listOf("命中兴趣「学科竞赛」"),
    keyPoints = listOf("报名截止：11 月 30 日", "以团队形式提交作品"),
)

@Preview(name = "资讯雷达 · 浅色", showBackground = true)
@Preview(
    name = "资讯雷达 · 深色",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun NewsScreenPreview() {
    ZZUPocketTheme {
        NewsScreen(
            state = NewsUiState(
                articles = listOf(previewArticle),
                lastUpdated = System.currentTimeMillis(),
                pendingCount = 3,
            ),
            onToggleTopic = {},
            onOnlyFollowedChange = {},
            onSortModeChange = {},
            onRefresh = {},
            onApplyPending = {},
            onOpenArticle = {},
            onOpenOriginal = {},
            onBack = {},
        )
    }
}
