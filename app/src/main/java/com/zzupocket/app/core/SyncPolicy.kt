package com.zzupocket.app.core

/**
 * 课表缓存的刷新时机。
 *
 * 用户与报告者定的策略是「进页面先显示缓存 → 后台自动刷新 → 拉不到就保留缓存并提示」，
 * 这里只回答其中一个问题：**这次进页面该不该联网**。
 *
 * TTL 取 12 小时：课表是学期级数据，一天最多拉两次就够；想立刻更新可以下拉/点刷新强制。
 * 这套逻辑本科与研究生共用（研究生的三种请求、本科的一种请求只是"抓取器"不同）。
 */
object SyncPolicy {

    /** 缓存有效期：12 小时 */
    const val TTL_MS = 12L * 60 * 60 * 1000

    /**
     * 是否需要联网刷新。
     *
     * @param now 现在（毫秒）
     * @param lastFetchedAt 上次**成功**抓取的时间；null / <= 0 = 从没成功过
     * @param ttlMs 有效期
     */
    fun shouldRefresh(now: Long, lastFetchedAt: Long?, ttlMs: Long = TTL_MS): Boolean {
        if (lastFetchedAt == null || lastFetchedAt <= 0L) return true
        // 系统时间被往回调过：当过期处理，免得"永远新鲜"
        if (now < lastFetchedAt) return true
        return now - lastFetchedAt >= ttlMs
    }
}
