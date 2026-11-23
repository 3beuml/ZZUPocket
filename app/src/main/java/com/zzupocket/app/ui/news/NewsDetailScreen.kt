package com.zzupocket.app.ui.news

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zzupocket.app.domain.news.NewsArticle
import com.zzupocket.app.domain.news.NewsCategory
import com.zzupocket.app.domain.news.NewsSection
import com.zzupocket.app.ui.components.GlassCard
import com.zzupocket.app.ui.components.HintText
import com.zzupocket.app.ui.theme.ZZUPocketTheme

/** 正文由主 agent 更新到 article.body；加载失败时保留已有正文。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsDetailScreen(
    article: NewsArticle,
    loading: Boolean = false,
    error: String? = null,
    aiConfigured: Boolean = false,
    aiSummary: String? = null,
    aiLoading: Boolean = false,
    aiError: String? = null,
    aiModel: String = "",
    onGenerateAi: () -> Unit = {},
    onOpenAiSettings: () -> Unit = {},
    onBack: () -> Unit,
    onOpenOriginal: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("资讯详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 2.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = article.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = listOf(
                    article.section.label,
                    article.publisher.ifBlank { "校园官网" },
                    article.publishedAt,
                    article.category.label,
                ).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { onOpenOriginal(article.url) },
                enabled = article.url.isNotBlank(),
            ) {
                Text("打开官网原文")
            }

            if (loading && article.body.isBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    SpacedText("正在加载正文…")
                }
            }
            error?.takeIf { it.isNotBlank() }?.let { message ->
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = if (article.body.isBlank()) "正文加载失败" else "暂时无法更新，已保留已有正文",
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

            if (article.keyPoints.isNotEmpty() || !article.eventDate.isNullOrBlank() ||
                !article.eventPlace.isNullOrBlank()
            ) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "本地要点",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        article.eventDate?.takeIf { it.isNotBlank() }?.let {
                            DetailInfo("活动时间", it)
                        }
                        article.eventPlace?.takeIf { it.isNotBlank() }?.let {
                            DetailInfo("活动地点", it)
                        }
                        article.keyPoints.forEach { point ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "•",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = point,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        HintText("由本机从公开正文提取，具体内容以官网原文为准")
                    }
                }
            }

            AiSummaryCard(
                article = article,
                bodyLoading = loading,
                configured = aiConfigured,
                summary = aiSummary,
                loading = aiLoading,
                error = aiError,
                model = aiModel,
                onGenerate = onGenerateAi,
                onOpenSettings = onOpenAiSettings,
            )

            ArticleBody(
                article = article,
                loading = loading,
                hasError = !error.isNullOrBlank(),
            )
        }
    }
}

@Composable
private fun SpacedText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp),
    )
}

@Composable
private fun DetailInfo(label: String, value: String) {
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AiSummaryCard(
    article: NewsArticle,
    bodyLoading: Boolean,
    configured: Boolean,
    summary: String?,
    loading: Boolean,
    error: String?,
    model: String,
    onGenerate: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("AI 提炼", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                if (model.isNotBlank()) {
                    Text(
                        model,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在提炼…", style = MaterialTheme.typography.bodySmall)
                }

                summary != null -> {
                    MarkdownText(
                        markdown = summary,
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "由 ${model.ifBlank { "AI" }} 生成，可能有误；具体以官网原文为准。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                bodyLoading -> Text(
                    "正文加载中，加载完成后可生成摘要。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                !configured -> {
                    Text(
                        "配置一个 OpenAI 兼容的摘要服务后，可以把这篇资讯提炼成要点。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onOpenSettings) { Text("配置 AI 提供商") }
                }

                error != null -> {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onGenerate) { Text("重试") }
                }

                else -> {
                    Text(
                        "生成时会把当前文章正文发送给你配置的 AI 服务商；只在你点击后发送。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onGenerate,
                        enabled = article.body.isNotBlank() || article.excerpt.isNotBlank(),
                    ) { Text("生成 AI 摘要") }
                }
            }
        }
    }
}

@Composable
private fun ArticleBody(
    article: NewsArticle,
    loading: Boolean,
    hasError: Boolean,
) {
    val paragraphs = article.body
        .split(Regex("\\r?\\n\\s*\\r?\\n"))
        .flatMap { block ->
            // 单行换行通常来自官网排版；保留独立段落，不把全文挤成一大段。
            if (block.contains('\n')) block.lines() else listOf(block)
        }
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            paragraphs.isNotEmpty() -> paragraphs.forEach { paragraph ->
                Text(
                    text = paragraph,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            loading -> Unit
            hasError -> Text(
                text = "可点击「打开官网原文」阅读完整内容。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Text(
                text = article.excerpt.ifBlank { "暂未获取到正文，可打开官网原文查看。" },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Preview(name = "资讯详情 · 浅色", showBackground = true)
@Preview(
    name = "资讯详情 · 深色",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun NewsDetailScreenPreview() {
    ZZUPocketTheme {
        NewsDetailScreen(
            article = NewsArticle(
                id = "detail-preview",
                title = "关于举办大学生创新竞赛校内选拔赛的通知",
                url = "https://www.zzu.edu.cn/",
                publishedAt = "2026-11-23",
                section = NewsSection.NOTICE,
                publisher = "教务处",
                excerpt = "校内选拔赛报名安排、作品要求与评审流程。",
                body = "各学院、各参赛团队：\n\n现将校内选拔赛有关安排通知如下。\n请按要求提交报名材料。",
                category = NewsCategory.COMPETITION,
                keyPoints = listOf("报名截止：11 月 30 日", "以团队形式提交作品"),
                eventDate = "2026-11-30 14:30",
                eventPlace = "主校区综合管理中心",
            ),
            onBack = {},
            onOpenOriginal = {},
        )
    }
}
