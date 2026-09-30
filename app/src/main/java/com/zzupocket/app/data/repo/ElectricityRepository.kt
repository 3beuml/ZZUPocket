package com.zzupocket.app.data.repo

import com.zzupocket.app.core.AppError
import com.zzupocket.app.data.auth.AuthRepository
import com.zzupocket.app.data.db.AppDatabase
import com.zzupocket.app.data.db.AppSettingsEntity
import com.zzupocket.app.data.db.EnergyReadingEntity
import com.zzupocket.app.data.ecard.EcardClient
import kotlinx.coroutines.CancellationException

/**
 * 电量仓库：**先落库、再从库读**。
 *
 * 这样断网或令牌过期时界面仍有数据可显示（配一个"数据截至 XX:XX"），而不是白屏。
 *
 * 照明与空调是**两套独立的电表**：接口同一个、参数同一套，只有 `room` 不同。
 * 因此这里按 accountType 分别存取，且**允许一个成功一个失败**（部分成功也要显示出来）。
 */
class ElectricityRepository(
    private val db: AppDatabase,
    private val ecard: EcardClient,
    private val auth: AuthRepository,
) {

    /** 单个表计的界面状态 */
    data class MeterState(
        val accountType: String,
        val roomId: String,
        val kwh: Double?,
        val fetchedAt: Long?,
        val error: Throwable?,
    )

    data class Snapshot(val light: MeterState, val ac: MeterState)

    /**
     * 未设置房间时的空值。
     *
     * ⚠️ 这里**故意不预置任何房间号**：房间号是每个宿舍自己的东西。
     * 早期版本把开发者自己的房间写成默认值，结果是别人装上 App 看到的是别人的电表
     * （同时等于把开发者的宿舍号公开了）。现在一律：没设置就什么都不查，由界面引导填写。
     *
     * 填写时的规律（主校区实测 148/148 条数据）：同一宿舍
     * **空调房间号 = 照明房间号 + 8**，例如 `…-101` 对应 `…-8101`。
     */

    fun observeLatest(accountType: String, roomId: String) =
        db.energyDao().observeLatest(accountType, roomId)

    suspend fun latest(accountType: String, roomId: String): EnergyReadingEntity? =
        db.energyDao().latest(accountType, roomId)

    /** 某段时间的读数（升序），用来算用电量差值与画余额折线 */
    suspend fun readingsSince(
        accountType: String,
        roomId: String,
        since: Long,
    ): List<EnergyReadingEntity> = db.energyDao().since(accountType, roomId, since)

    /** 一次刷新两个表计；任何一边失败都不影响另一边出数 */
    suspend fun refreshAll(
        lightRoomId: String = NO_ROOM,
        acRoomId: String = NO_ROOM,
    ): Snapshot = Snapshot(
        light = attempt(ACCOUNT_TYPE_LIGHT, lightRoomId),
        ac = attempt(ACCOUNT_TYPE_AC, acRoomId),
    )

    private suspend fun attempt(accountType: String, roomId: String): MeterState {
        // 还没设置房间：既不打服务端也不报错，界面会引导用户去设置
        if (roomId.isBlank()) {
            return MeterState(accountType, roomId, null, null, null)
        }
        return try {
            val reading = refresh(accountType, roomId)
            MeterState(accountType, roomId, reading.kwh, reading.ts, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 失败时不清空：把库里最后一次读数拿出来继续显示，只是标记为过期 + 带错误
            val cached = db.energyDao().latest(accountType, roomId)
            MeterState(accountType, roomId, cached?.kwh, cached?.ts, e)
        }
    }

    /**
     * 拉一次最新读数并落库。
     * 一卡通令牌失效（401/403）会被翻译成"需要重新登录"。
     */
    suspend fun refresh(accountType: String, roomId: String): EnergyReadingEntity {
        val token = auth.requireEcardAccessToken()
        val kwh = try {
            ecard.queryRemainingKwh(token, roomId)
        } catch (e: AppError.Server) {
            if (e.code == 401 || e.code == 403) {
                throw AppError.ReLoginRequired("一卡通会话已过期，请重新登录")
            }
            throw e
        }
        val now = System.currentTimeMillis()
        val entity = EnergyReadingEntity(
            accountType = accountType,
            roomId = roomId,
            ts = now,
            kwh = kwh,
            updatedAt = now,
        )
        db.energyDao().insert(entity)
        return entity
    }

    // ── 设置 ──────────────────────────────────────────────────────────

    suspend fun settings(): AppSettingsEntity = db.settingsDao().get() ?: AppSettingsEntity()

    /**
     * 两个表计实际该用哪两个房间号：**用户设置优先，其次本机已有读数里的房间号**。
     *
     * 为什么不预置默认值：房间号是每间宿舍自己的，写死别人的会让别人查到不相干的电表。
     * 但直接从老版本升上来的用户，房间号从来没存进设置（老版本是写死在代码里的），
     * 所以这里从本地读数反推一次，避免他们升级后突然"未设置"。
     */
    suspend fun effectiveRooms(): Pair<String, String> {
        val s = settings()
        val light = s.lightRoomId?.takeIf { it.isNotBlank() }
            ?: db.energyDao().latestRoomId(ACCOUNT_TYPE_LIGHT).orEmpty()
        val ac = s.acRoomId?.takeIf { it.isNotBlank() }
            ?: db.energyDao().latestRoomId(ACCOUNT_TYPE_AC).orEmpty()
        return light to ac
    }

    suspend fun saveSettings(settings: AppSettingsEntity) {
        db.settingsDao().upsert(settings.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun readingCount(): Int = db.energyDao().count()

    companion object {
        /** 未设置房间时的空值（见类顶部说明：故意不预置任何房间号） */
        const val NO_ROOM: String = ""

        /** 照明 / 寝室电费 */
        const val ACCOUNT_TYPE_LIGHT = "light"

        /** 空调电费（同一接口，只是 room 不同） */
        const val ACCOUNT_TYPE_AC = "ac"
    }
}
