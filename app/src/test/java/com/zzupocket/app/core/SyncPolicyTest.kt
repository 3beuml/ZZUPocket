package com.zzupocket.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「什么时候该联网刷新」的回归测试。
 *
 * 这是缓存层最容易写错、又最难在真机上看出问题的一处：
 * 判成"总是刷新"就等于没有缓存；判成"永不刷新"就永远看不到调课。
 */
class SyncPolicyTest {

    private val hour = 60L * 60 * 1000

    @Test
    fun `从没抓过就要刷新`() {
        assertTrue(SyncPolicy.shouldRefresh(now = 0L, lastFetchedAt = null))
        assertTrue(SyncPolicy.shouldRefresh(now = 5 * hour, lastFetchedAt = 0L))
        assertTrue(SyncPolicy.shouldRefresh(now = 5 * hour, lastFetchedAt = -1L))
    }

    @Test
    fun `刚抓过不刷新`() {
        val now = 100 * hour
        assertFalse(SyncPolicy.shouldRefresh(now, lastFetchedAt = now - hour))
        assertFalse(SyncPolicy.shouldRefresh(now, lastFetchedAt = now - 11 * hour))
    }

    @Test
    fun `到 12 小时就刷新`() {
        val now = 100 * hour
        assertFalse(SyncPolicy.shouldRefresh(now, lastFetchedAt = now - 12 * hour + 1))
        assertTrue(SyncPolicy.shouldRefresh(now, lastFetchedAt = now - 12 * hour))
        assertTrue(SyncPolicy.shouldRefresh(now, lastFetchedAt = now - 30 * hour))
    }

    @Test
    fun `系统时间回拨时当作过期`() {
        val now = 100 * hour
        assertTrue(SyncPolicy.shouldRefresh(now, lastFetchedAt = now + hour))
    }

    @Test
    fun `可以自定义 TTL`() {
        val now = 100 * hour
        assertFalse(SyncPolicy.shouldRefresh(now, lastFetchedAt = now - 30 * 60 * 1000L, ttlMs = hour))
        assertTrue(SyncPolicy.shouldRefresh(now, lastFetchedAt = now - hour, ttlMs = hour))
    }
}
