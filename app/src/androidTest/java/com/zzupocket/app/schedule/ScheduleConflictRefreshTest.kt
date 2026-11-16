package com.zzupocket.app.schedule

import android.content.Context
import android.content.ContextWrapper
import android.util.Base64
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzupocket.app.core.SyncPolicy
import com.zzupocket.app.data.auth.AuthRepository
import com.zzupocket.app.data.auth.CasClient
import com.zzupocket.app.data.auth.TokenStore
import com.zzupocket.app.data.db.AppDatabase
import com.zzupocket.app.data.eas.EasClient
import com.zzupocket.app.data.ecard.EcardClient
import com.zzupocket.app.data.repo.ScheduleRepository
import com.zzupocket.app.domain.Course
import com.zzupocket.app.domain.Session
import com.zzupocket.app.domain.conflictDisplayKey
import com.zzupocket.app.domain.scheduleBlocks
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** 真实 EAS 解析与 Room 同步；所有 HTTP 请求在拦截器内返回，令牌和偏好也完全隔离。 */
@RunWith(AndroidJUnit4::class)
class ScheduleConflictRefreshTest {
    private val prefix = "conflict-refresh-${UUID.randomUUID()}"
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: ScheduleRepository
    private var responseCode = 200
    private val requests = AtomicInteger()
    private val legacyId = ScheduleRepository.sessionIdOf(194, "A", 7, 2, 9, "2026-10-20")
    private val theory = Session(
        id = legacyId, semesterId = 194, source = "eas", courseCode = "A", courseName = "工程制图",
        weekIndex = 7, weekday = 2, startUnit = 9, endUnit = 10, date = "2026-10-20",
        startTime = "19:00", endTime = "20:40", teacherName = null, roomText = null,
        campusText = null, color = null,
    )
    private val experiment get() = theory.copy(id = "$legacyId:experiment", experiment = true)

    @Before fun open() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.databaseBuilder(context, AppDatabase::class.java, "$prefix.db").build()
        val isolatedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                context.getSharedPreferences("$prefix-$name", mode)
        }
        // 无签名的合成令牌，仅通过本地有效期检查，不读取/替换应用的真实登录态。
        val payload = Base64.encodeToString(
            "{\"exp\":${System.currentTimeMillis() / 1000 + 86_400}}".toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val store = TokenStore(isolatedContext).apply { casUserToken = "fixture.$payload.fixture" }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.encodedPath.endsWith("/course-table/194"))
            check(request.url.queryParameter("hasExperiment") == "true")
            requests.incrementAndGet()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(responseCode)
                .message("test fixture")
                .body(FIXTURE.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val json = Json { ignoreUnknownKeys = true }
        val auth = AuthRepository(store, CasClient(client, json, "test-device"), EcardClient(client, json))
        repo = ScheduleRepository(db, EasClient(client, json), auth)
    }

    @After fun close() {
        db.close()
        context.deleteDatabase("$prefix.db")
        context.deleteSharedPreferences("$prefix-secure_prefs")
    }

    private suspend fun seedLegacyCache() {
        // v3 的实验记录使用理论课主键，并覆盖了同时间的理论记录；另放一条已失效的教务记录。
        val oldRows = listOf(experiment.copy(id = legacyId), theory.copy(id = "obsolete", startUnit = 1, endUnit = 2))
        repo.saveTimetable("eas", 194, oldRows, listOf(Course("194:A", 194, "A", "工程制图", 0.0, null, emptyList(), oldRows)))
        val old = db.courseSessionDao().bySemester(194).first()
        db.courseSessionDao().upsertAll(listOf(old.copy(
            id = "custom-stays", courseKey = "194:P-test", source = "custom", courseName = "自定义日程",
            startUnit = 5, endUnit = 6,
        )))
        val sync = requireNotNull(db.timetableSyncDao().get("eas", 194))
        db.timetableSyncDao().upsert(sync.copy(cacheVersion = 3))
        val block = scheduleBlocks(listOf(theory, experiment)).single()
        repo.saveConflictDisplay(block, setOf(7), experiment.conflictDisplayKey())
    }

    @Test fun oldCacheForcesRefreshWithoutDuplicatingTheoryOrExperiment() = runBlocking {
        seedLegacyCache()
        assertEquals(4, ScheduleRepository.CACHE_VERSION)
        assertNull(repo.lastFetchedAt("eas", 194))
        assertTrue(SyncPolicy.shouldRefresh(System.currentTimeMillis(), repo.lastFetchedAt("eas", 194)))
        assertTrue(repo.hasCourseData(194)) // 版本过期仍保留可显示的离线数据。
        repeat(3) {
            repo.refreshCourseTable(194)
            val rows = db.courseSessionDao().bySemesterAndSource(194, "eas")
            assertEquals(setOf(legacyId, "$legacyId:experiment"), rows.map { it.id }.toSet())
            assertEquals(2, rows.size)
            assertEquals(setOf(false, true), rows.map { it.experiment }.toSet())
            assertEquals(1, db.courseSessionDao().bySemesterAndSource(194, "custom").size)
            val block = scheduleBlocks(repo.cachedSessions("eas", 194), repo.conflictDisplayChoices(194)).single()
            assertEquals(experiment.conflictDisplayKey(), block.selectedSession?.conflictDisplayKey())
            assertNotNull(repo.lastFetchedAt("eas", 194))
            assertFalse(SyncPolicy.shouldRefresh(System.currentTimeMillis(), repo.lastFetchedAt("eas", 194)))
        }
        assertEquals(3, requests.get())
    }

    @Test fun failedUpgradeRefreshKeepsOldRowsAndLocalChoice() = runBlocking {
        seedLegacyCache()
        val rows = db.courseSessionDao().bySemester(194)
        val choices = repo.conflictDisplayChoices(194)
        responseCode = 503
        assertNotNull(runCatching { repo.refreshCourseTable(194) }.exceptionOrNull())
        assertEquals(rows, db.courseSessionDao().bySemester(194))
        assertEquals(choices, repo.conflictDisplayChoices(194))
        assertNull(repo.lastFetchedAt("eas", 194))
        assertEquals(3, db.timetableSyncDao().get("eas", 194)?.cacheVersion)
        assertEquals(1, requests.get())
    }

    private companion object {
        val FIXTURE = """{"result":0,"data":[{"course":{"code":"A","nameZh":"工程制图"},"schedules":[
            {"weekIndex":7,"weekday":2,"startUnit":9,"endUnit":10,"date":"2026-10-20","lessonType":"THEORY"},
            {"weekIndex":7,"weekday":2,"startUnit":9,"endUnit":10,"date":"2026-10-20","lessonType":"EXPERIMENT"}
        ]}]}"""
    }
}
