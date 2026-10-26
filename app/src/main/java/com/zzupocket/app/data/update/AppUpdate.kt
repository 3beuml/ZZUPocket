package com.zzupocket.app.data.update

import android.content.Context
import com.zzupocket.app.core.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 一次可用的「新版本」信息。字段全部来自 GitHub Releases 的 latest 接口：
 *
 *  · [version] —— tag 去掉 v/V 前缀后的主版本（与本地 versionName 同口径比较）；
 *  · [notes]   —— release body，就是发布时写的更新说明；
 *  · [pageUrl] —— release 页（浏览器兜底入口）；
 *  · [downloadUrl] —— assets 里第一个 .apk 的直链（走 GitHub 的 CDN，校园网直连不保证）。
 */
data class AppRelease(
    val version: String,
    val notes: String,
    val pageUrl: String,
    val downloadUrl: String?,
)

/**
 * 检测更新的纯逻辑（有单测，见 AppUpdateTest）。
 *
 * 版本比较刻意用 **versionName 语义比较**而不是 versionCode：
 * versionCode 是构建期间的数字，远端 release 只有 tag/versionName，
 * 用同一口径即可；激进用户从 0.32.4 direct 跳 0.33.0 也天然正确。
 */
object AppUpdate {

    /**
     * 一次 release 到另一个 release 的比较：1 = a 更新，0 = 相同，-1 = a 更旧。
     *
     * 口径（对 AppUpdateTest 承诺）：
     *  · 前缀 v/V 自动剥掉；每段只取**开头连续数字**当值（"0-rc1" 当 0），非法段当 0；
     *  · 数字段严格相等时，走**预发后缀规则**：无后缀（正式版）比带后缀新 ——
     *    `0.33.0-rc1 < 0.33.0`，这对装机了 rc 的用户意味着：正式版发布后红点会亮；
     *  · 两个都带后缀（rc 之间）比后缀里的数字（rc2 > rc1）。
     */
    fun compareVersions(a: String?, b: String?): Int {
        fun parts(v: String?): Pair<List<Int>, String?> {
            if (v == null) return emptyList<Int>() to null
            val s = v.trim().removePrefix("v").removePrefix("V")
            val digits = Regex("^\\d+(?:\\.\\d+)*").find(s)?.value
            val core = digits ?: ""
            val suffix = if (core.length < s.length) s.drop(core.length).trim() else null
            val nums = core.split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
                .takeIf { core.isNotEmpty() } ?: emptyList()
            return nums to suffix
        }
        val (left, leftSuffix) = parts(a)
        val (right, rightSuffix) = parts(b)
        val n = maxOf(left.size, right.size)
        for (i in 0 until n) {
            val x = left.getOrElse(i) { 0 }
            val y = right.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y).coerceIn(-1, 1)
        }
        // 数字打平：预发后缀定先后 —— 正式 > 预发；rc 之间比后缀数字
        if (leftSuffix != rightSuffix) {
            if (leftSuffix == null) return 1
            if (rightSuffix == null) return -1
            fun tail(s: String): Int =
                Regex("\\d+").find(s)?.value?.toIntOrNull() ?: 0
            return tail(leftSuffix).compareTo(tail(rightSuffix)).coerceIn(-1, 1)
        }
        return 0
    }

    /**
     * 红点 = 远端有比本地**新**的版本，且不是用户已经点过「不再提醒」的那一个。
     * 屏蔽只认具体版本：之后出了更新的版本，仍然会提醒 —— 所以这里只判不等。
     */
    fun shouldShowDot(current: String?, latest: String?, muted: String?): Boolean =
        latest != null &&
            compareVersions(latest, current) > 0 &&
            (muted == null || compareVersions(latest, muted) != 0)

    /** 本机 versionName；读不到（理论不可能）返回空串，红点判定的所有比较都按「最旧」处理 */
    fun localVersion(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
}

/** GitHub Releases 的只读拉取。公开仓不需要 token；访问 `api.github.com` 即可，`github.com` 被校园网拦也不影响检测。 */
object AppReleaseClient {

    private const val RELEASES_URL =
        "https://api.github.com/repos/3beuml/ZZUPocket/releases/latest"

    /** 检测只求「问一下」：起连/整次都用短超时，开屏静默场景与手动场景都不该卡很久 */
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun latest(): AppRelease? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(RELEASES_URL)
                .header("Accept", "application/vnd.github+json")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                val version = root["tag_name"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (version.isBlank()) return@withContext null
                val assets = root["assets"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
                val apk = assets.map { it.jsonObject }
                    .firstOrNull { (it["name"]?.jsonPrimitive?.content ?: "").endsWith(".apk", ignoreCase = true) }
                AppRelease(
                    version = version.removePrefix("v").removePrefix("V"),
                    notes = root["body"]?.jsonPrimitive?.content.orEmpty(),
                    pageUrl = root["html_url"]?.jsonPrimitive?.content
                        ?: "https://github.com/3beuml/ZZUPocket/releases/latest",
                    downloadUrl = apk?.get("browser_download_url")?.jsonPrimitive?.content,
                )
            }
        }.getOrNull()
    }.takeIf { it?.version?.isNotBlank() == true }
}

/**
 * 检测更新的进程内状态中心：更多页入口、弹窗、dock 角标共享同一份。
 *
 *  · 开屏静默查一次（[checkSilently]，失败无声，红点不误报也不打扰）；
 *  · 手动查（[checkExplicit]）给弹窗完整的 Checking→结论 状态；
 *  · 「不再提醒」落在 UiPrefsStore（[setUpdateMutedVersion]），重启后依然生效。
 */
object UpdateCenter {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _release = MutableStateFlow<AppRelease?>(null)
    /** 最近一次成功拉到的远端 release；null = 还没查到过（不等于没有新版） */
    val release: StateFlow<AppRelease?> = _release

    /** 手动检查的阶段性状态，弹窗用来显示「正在检查 / 已是最新 / 网络不通」 */
    sealed interface Phase {
        data object Idle : Phase
        data object Checking : Phase
        data object Done : Phase
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase

    /** 被「不再提醒」的版本号；持久化在 UiPrefsStore，重启后依然生效 */
    val mutedVersion: StateFlow<String?> =
        AppGraph.uiPrefsStore.flow
            .map { it.updateMutedVersion }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** 静默检查：只改 [release]，失败保持 null（红点自然不亮） */
    fun checkSilently() {
        scope.launch { _release.value = AppReleaseClient.latest() }
    }

    /** 手动检查：完整走一遍 Checking → Done，结论状态由 phase + release 表达 */
    fun checkExplicit() {
        scope.launch {
            _phase.value = Phase.Checking
            val result = AppReleaseClient.latest()
            _release.value = result
            _phase.value = Phase.Done
        }
    }

    suspend fun setUpdateMutedVersion(v: String?) {
        AppGraph.uiPrefsStore.setUpdateMutedVersion(v)
    }
}
