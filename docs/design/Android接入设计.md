# 郑大校园助手 — Android 接入设计

> 前置结论见 `郑大校园助手-接口现状核查.md`。本文只讲：**接口在 App 里怎么调、数据怎么存、怎么处理成你要的功能。**

---

## 一、总体分层

```
UI 层 (Compose + ViewModel)
    │  只认 domain model
    ▼
domain 层 (UseCase + Model)
    │  Course / Classroom / Reservation / ElectricityReading / Transaction / StudySession
    ▼
data 层 (Repository → RemoteDataSource + LocalDataSource)
    │
    ├── remote/eas       教务   jwxt.zzu.edu.cn
    ├── remote/ecard     一卡通  ecard.v.zzu.edu.cn
    ├── remote/library   图书馆  order.lib.zzu.edu.cn
    └── local/room       Room（全部历史数据）
    ▲
core 层：network（OkHttp 工厂/拦截器）、auth（CAS 令牌中枢）、crypto（凭据加密）
```

**一条铁律**：UI 层永远不直接碰 HTTP。所有接口结果先落 Room，UI 从 Room 读。这样"没网/令牌过期"时界面仍有数据可显示，只是标注"数据截至 XX:XX"。

---

## 二、认证设计（整个 App 的核心）

### 2.1 令牌流向

```
        学号 + 密码 + 短信验证码（仅首次 / 令牌失效时）
                        │
                        ▼
              ┌─────────────────────┐
              │  CAS  cas.s.zzu.edu.cn │
              │  deviceId = 固定字符串  │   ← 必须自定义，不要用 "ZZU.Py"
              └─────────────────────┘
                        │
              userToken + refreshToken          ← 持久化（加密存储）
                        │
        ┌───────────────┼────────────────────┐
        ▼               ▼                    ▼
   教务 EAS        一卡通 ECard          图书馆(可选 CAS 登录)
   header:         ① /server/auth/host/open → tid
   Authorization:  ② /server/auth/getToken → accessToken + refreshToken
   userToken       ③ 每 45 分钟刷新
   cookie: userToken
```

关键点：

1. **只持久化 token，不持久化密码**。首次登录拿到 `userToken`/`refreshToken` 后，后续一律 `set_token()` 复用。
2. **绝不在后台轮询里做账密登录**——会通过 `userOnlineDetect` 把手机上的豫见郑大踢下线。
3. **deviceId 用自己 App 的固定值**（如包名），并引导用户去 `authx-service.s.zzu.edu.cn/security-center/eqIP-management` 设为可信设备，之后就基本不再要短信码。
4. **一卡通是二级令牌**：用 `userToken` 换 `tid`，再用 `tid` 换 `accessToken`，45 分钟过期。
5. **图书馆是独立令牌**：`/v4/login/login` 后前端存 sessionStorage，我们存本地加密存储。

### 2.2 AuthManager 设计

```kotlin
interface TokenStore {
    suspend fun casTokens(): CasTokens?          // userToken, refreshToken
    suspend fun ecardTokens(): EcardTokens?      // accessToken, refreshToken
    suspend fun libraryToken(): String?
    suspend fun saveCas(t: CasTokens)
    suspend fun saveEcard(t: EcardTokens)
}

class AuthManager(
    private val store: TokenStore,
    private val casApi: CasApi,
    private val ecardApi: EcardApi,
) {
    private val deviceId = BuildConfig.APP_DEVICE_ID   // 固定字符串

    // 单飞：并发请求同时发现过期时，只刷新一次
    private val casRefresh = Mutex()
    private val ecardRefresh = Mutex()

    suspend fun requireCasToken(): String {
        store.casTokens()?.takeIf { !it.expiringSoon() }?.let { return it.userToken }
        return casRefresh.withLock {
            store.casTokens()?.takeIf { !it.expiringSoon() }?.userToken
                ?: refreshCasLocked()
        }
    }

    private suspend fun refreshCasLocked(): String {
        // 优先用 refreshToken；失败才回退到账密登录（且需要 MFA，必须在前台）
        return runCatching { casApi.refresh(store.casTokens()!!.refreshToken) }
            .getOrElse { throw ReLoginRequired() }   // 交给 UI 弹登录页
    }
}
```

`ReLoginRequired` 异常一路冒泡到 UI，弹出登录页（需要短信码，**必须在前台**）。这是唯一需要用户介入的路径。

---

## 三、网络层

### 3.1 每个系统一个 OkHttpClient

**必须隔离**：教务和图书馆都依赖 cookie/token，共用一个 client 会互相污染会话。

```kotlin
object HttpClients {
    private fun base() = OkHttpClient.Builder()
        .connectTimeout(15, SECONDS)
        .readTimeout(20, SECONDS)
        .addInterceptor(HeaderInterceptor())      // 复刻 UA/Referer/X-Requested-With
        .addInterceptor(RateLimitInterceptor())   // 令牌桶，防风控
        .addInterceptor(LogInterceptor())         // 脱敏日志
        .build()

    val eas     = base().newBuilder().cookieJar(EasCookieJar).build()
    val ecard   = base().newBuilder().build()     // 一卡通走 header 令牌
    val library = base().newBuilder().cookieJar(LibCookieJar).build()
}
```

### 3.2 请求头必须复刻

学校服务对 UA / Referer 敏感，缺了可能被拒：

```kotlin
class HeaderInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val origin = chain.request()
        val host = origin.url.host
        val ua = when {
            host.contains("order.lib") -> UA_MICROMESSENGER   // 图书馆走微信 UA（H5 原是公众号入口）
            else -> UA_ANDROID_OKHTTP
        }
        return chain.proceed(origin.newBuilder()
            .header("User-Agent", ua)
            .header("Accept", "application/json, text/plain, */*")
            .apply { if (host.contains("ecard")) header("X-Requested-With", "com.newcapec.mobile.ncp") }
            .build())
    }
}
```

### 3.3 401 统一处理 + 单飞刷新

```kotlin
class AuthAuthenticator(
    private val tag: String,                  // "eas" / "ecard" / "library"
    private val auth: AuthManager,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null       // 最多重试一次，避免死循环
        val newToken = runBlocking {
            when (tag) {
                "eas"     -> auth.requireCasToken()
                "ecard"   -> auth.requireEcardToken()
                else      -> auth.reloginLibrary()
            }
        }
        return response.request.newBuilder()
            .header("Authorization", newToken)
            .build()
    }
    private fun responseCount(r: Response): Int {
        var n = 1; var p = r.priorResponse
        while (p != null) { n++; p = p.priorResponse }
        return n
    }
}
```

### 3.4 限流拦截器（现在是刚需）

证据：你旧项目 1 分钟轮询 15 分钟，16 条读数完全相同——**请求全部是浪费**。加一个令牌桶，从架构上杜绝高频：

```kotlin
class RateLimitInterceptor(
    private val permitsPerMinute: Int = 6,       // 每个 host 每分钟
) : Interceptor {
    private val buckets = ConcurrentHashMap<String, Bucket>()
    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        buckets.getOrPut(host) { Bucket(permitsPerMinute) }.acquire()   // 阻塞直到有令牌
        return chain.proceed(chain.request())
    }
}
```

---

## 四、四套接口的接入清单

| 系统 | 调用方式 | 注意 |
|---|---|---|
| **CAS** | POST JSON，返回 `userToken`/`refreshToken` | `deviceId` 固定；MFA 在前台 |
| **教务 EAS** | header `Authorization: userToken` **+** cookie `userToken`（域 `.zzu.edu.cn`）<br>`/eams-micro-server/api/v1/lesson/student/course-table`<br>`/eams-micro-server/api/v1/semester/current-semester`<br>`/eams-door/api/v1/calendar/get-all-semesters` | 空教室需抓包补 |
| **一卡通** | `Authorization: accessToken` header<br>POST `/server/utilities/config`（默认房间）<br>POST `/server/utilities/account`（电量，body 带 `utilityType`）<br>`info.s.zzu.edu.cn/portal-api/.../get-person-info-card-list`（余额，用 userToken） | **照明 vs 空调 = 同一个接口、同一套参数，只换 `room`**（已由 ZZU-Electricity-Monitor 验证）<br>交易/用电明细待挖 chunk |
| **图书馆** | POST JSON，token 在 header<br>`/v4/member/seat`（预约记录）<br>`/v4/Space/map`、`/v4/Space/seat`（座位）<br>`/v4/member/seatRenege`（违约） | **校外只能 HTTP:80**<br>座位数据不缓存 |

一卡通电量的请求体（来自 zzupy 源码，已验证）。**注意 `utilityType` 恒为 `"electric"`，照明与空调只差 `room`**：

```kotlin
@Serializable
data class EnergyQuery(
    val utilityType: String = "electric",   // 固定值，照明/空调都一样
    val bigArea: String = "",
    val area: String,          // 从 roomId 拆分：'99-16--32-8101'
    val building: String,      // → area=99, building=38, level=74, room=99-16--32-8101
    val unit: String = "",
    val level: String,
    val room: String,          // ← 唯一变化点：照明房间号 / 空调房间号
    val subArea: String = "",
)
```

`roomId` 拆分规则：`area-building--level-room`。

**照明 / 空调房间号从哪来**：不要逐级调 `get_room_dict()`。用 ZZU-Electricity-Monitor 的房间查询器静态数据（6 个校区、0.7MB，结构见核查文档 13.3），让用户按「校区 → 楼栋 → 房间」选一次，同时拿到两个房间号：

```kotlin
data class RoomPair(val building: String, val lightRoom: String, val acRoom: String)
// 例：松园15号楼 → 照明 99-16--31-xxx / 空调 99-16--32-8xxx
```

---

## 五、数据层：Room 表设计

```kotlin
// 课表
@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey val id: String,          // 学期+课程+时间+地点的稳定哈希
    val semesterId: Long,
    val name: String,                    // course.name_zh
    val teacher: String?,
    val location: String?,
    val weekday: Int,                    // 1..7
    val startUnit: Int, val endUnit: Int,// 1..10
    val weeks: String,                   // 如 "1-16周" 原始串，解析成 bitset 更好
    val raw: String,                     // 原始 JSON，改版时能回滚解析
)

// 电量读数（电费 / 空调费 两类，各自房间）
@Entity(tableName = "energy_readings", primaryKeys = ["accountType","roomId","ts"])
data class EnergyReadingEntity(
    val accountType: String,             // "electric" / "ac"
    val roomId: String,
    val ts: Long,                        // epoch millis UTC
    val kwh: Double,                     // 允许负值（欠费）
    val fetchedAt: Long,
)

// 校园卡交易
@Entity(tableName = "card_transactions")
data class TransactionEntity(
    @PrimaryKey val id: String,
    val ts: Long, val amount: Double,    // 负=消费，正=充值
    val merchant: String?, val place: String?, val type: String?,
)

// 图书馆预约记录
@Entity(tableName = "library_reservations")
data class ReservationEntity(
    @PrimaryKey val id: String,
    val areaName: String?, val seatName: String?, val seatId: String?,
    val beginTs: Long, val endTs: Long,
    val status: String,                  // 含「未签到」
    val renegeTs: Long?,
)

// 番茄钟（唯一由用户主动产生的数据）
@Entity(tableName = "study_sessions")
data class StudySessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTs: Long, val endTs: Long, val durationSec: Int,
    val tag: String,                     // 用户贴的标签
    val courseId: String?,               // 可选：关联课表课程
    val source: String = "pomodoro",     // pomodoro / manual
    val completed: Boolean,              // 是否走完计划时长
)

// 通用缓存（含 TTL，用于非时序数据）
@Entity(tableName = "kv_cache")
data class KvCacheEntity(
    @PrimaryKey val key: String,         // "today_balance" / "seat_map_3F"
    val payload: String, val fetchedAt: Long, val ttlSeconds: Long,
)
```

**为什么全部落库**：
- 热力图 / 周月年总结需要历史，不能只靠内存
- 令牌过期、断网时仍可展示
- 电量差分只能靠本地时序库

---

## 六、数据怎么"处理使用"（按你的功能逐条）

### 6.1 课表 → 今日课表与番茄钟联动

```kotlin
// 1) 拉到整学期 7×10 网格 → 铺平成 courses
// 2) 由「学期起始日 + 当前日期」算教学周
suspend fun currentWeek(now: LocalDate): Int? =
    eas.getWeekIndex(now)      // EAS 自带接口，返回 null 表示不在教学周

// 3) 生成今日时间轴事件（番茄钟状态机的输入）
sealed interface TimelineEvent {
    data class Class(val course: Course, val start: Instant, val end: Instant) : TimelineEvent
    data class Break(val start: Instant, val end: Instant) : TimelineEvent
    data class Free(val start: Instant, val end: Instant) : TimelineEvent
}
```

节次→时钟时间需要一张**本地节次时刻表**（第 1 节 8:00–8:50 …），这是学校作息，建议做成可配置常量（调休/冬夏作息可改）。

### 6.2 空教室 → 本地推导，不轮询

```
拉取教室课表（教学楼 × 周次）  →  落 Room（classroom_schedule）
        ↓
本地计算：某教学楼 + 某节次 + 目标周次 → 未被占用的教室集合
        ↓
"现在哪里空" = 用当前时间的节次去查
```

数据量大，只缓存**目标教学楼 + 当前周**即可。

### 6.3 电量 → 日/月用量

```sql
-- 日用量 = 当天首条 - 当天末条（首条 > 末条说明当天有充值，要单独处理）
SELECT date(ts/1000,'unixepoch','localtime') AS d,
       MIN(kwh) AS kmin, MAX(kwh) AS kmax
FROM energy_readings
WHERE accountType='electric' AND roomId=:room
GROUP BY d;
```

注意事项：
- **负值是正常的**（欠费），UI 要能正常显示，不能当异常
- 当天出现"末条 > 首条"→ 说明发生了充值，差值要标为"充值日"，不能算成负消耗
- **预估可用天数**沿用你旧项目的算法：`剩余电量 / 昨日日均`
- 采样足够稀（30–60 分钟）即可，因为服务端本身更新很慢

### 6.4 校园卡交易 → 增量拉取 + 列表

```kotlin
// 首次全量，之后按 lastSyncTs 增量
suspend fun syncTransactions() {
    val last = dao.maxTs() ?: 0
    val page = api.tradeRecord(page = 1, limit = 50)
    dao.upsertAll(page.items.map { it.toEntity() }.filter { it.ts > last })
}
```

UI 就是一个分组列表（按日分组）+ 月度合计。

### 6.5 图书馆预约记录 → 独立实体，**不与番茄钟合并**

```kotlin
// 只统计"正常完成"的记录（过滤掉 未签到 = 爽约）
val attended = dao.reservations()
    .filter { it.status != "未签到" }
```

**展示上分两条轨**：时间轴上一栏番茄钟专注块，一栏图书馆预约时段。不要合成单一"学习时长"数字。

### 6.6 热力图

```sql
-- 按天聚合番茄钟时长；用 NULL 表示"无记录"，与 0 区分
SELECT date(startTs/1000,'unixepoch','localtime') AS d,
       SUM(durationSec) AS secs,
       COUNT(*) AS sessions
FROM study_sessions
GROUP BY d;
```

```kotlin
// 关键：区分「无记录」和「零时长」
val cell: HeatCell = row?.let { HeatCell(it.secs, it.sessions) }
    ?: HeatCell.NoRecord       // 灰色斜纹，而不是最浅色
```

### 6.7 周 / 月 / 年总结

```sql
-- 标签分布（回答"常干什么"）
SELECT tag, SUM(durationSec) AS secs, COUNT(*) AS n
FROM study_sessions
WHERE startTs BETWEEN :from AND :to
GROUP BY tag ORDER BY secs DESC;

-- 图书馆频率
SELECT area_name, COUNT(*) AS visits, SUM(endTs-beginTs)/3600000.0 AS hours
FROM library_reservations
WHERE beginTs BETWEEN :from AND :to AND status != '未签到'
GROUP BY area_name;
```

**"周"的定义**：用自然周（周一 00:00 起），同时显示对应教学周（"第 8 教学周"）做参照。假期自然落入自然周，无需特殊处理。

---

## 七、后台与时效性

| 任务 | 机制 | 周期 |
|---|---|---|
| 电量 / 空调费 | WorkManager（约束：有网） | **30–60 分钟**（服务端更新慢，1 分钟无意义） |
| 低电量告警 | 在前一次拉取后判断 → 发通知 | 同上 |
| 座位实时数据 | **不后台轮询**，只在打开页面时查 | 交互触发 |
| 预约记录 | WorkManager | 每天 1–2 次 |
| 交易记录 | WorkManager | 每天 1 次 |
| 番茄钟计时 | **前台服务** + `SystemClock.elapsedRealtime()` | 用户触发 |
| 上课/下课/课间提醒 | **AlarmManager** 精确闹钟 | 按课表 |

**计时正确性**（重要）：

```kotlin
// 错误：靠自增计数器，Doze 期间会停
// 正确：记录锚点，用单调时钟推算
val startElapsed = SystemClock.elapsedRealtime()
val startWall = System.currentTimeMillis()
fun elapsedSec() = (SystemClock.elapsedRealtime() - startElapsed) / 1000
// 落库时用 startWall + elapsedSec() 反推 endTs，保证跨 Doze 也准确
```

**必须在 UI 标注数据新鲜度**：

```
剩余电量  12.4 度     数据截至 14:32  ⚠️ 3 小时前
```

而不是假装实时。

---

## 八、工程细节踩坑清单

1. **令牌单飞刷新**：并发请求同时 401 → 只刷新一次，其余等待复用（见 2.2）
2. **Cookie 隔离**：教务与图书馆各自独立 CookieJar
3. **请求头复刻**：UA / Referer / X-Requested-With 缺失可能被拒
4. **401 重试限一次**：避免死循环
5. **宽松解析**：`ignoreUnknownKeys = true`，字段缺失给默认值，**绝不因一个字段崩掉整个列表**
6. **远端接口配置**：URL、字段路径抽成可下发的 JSON，改版时改配置即可热修，不用发版
7. **日志脱敏**：绝不打印 token / 密码 / 学号；release 关闭 body 日志
8. **Mock 层 + 快照测试**：把真实响应存 fixture，对解析层做快照测试——接口挂了也能开发
9. **Room 迁移**：历史学习数据不能丢，每次改表都要写 Migration
10. **负值/异常值**：电量欠费为负；单日用量为负说明当天充值
11. **时间戳统一**：一律存 epoch millis（UTC），只在展示时转本地时区
12. **release 关闭用户证书信任**：抓包用的 networkSecurityConfig 只留 debug

---

## 九、实施切片建议

| 切片 | 内容 | 产出 |
|---|---|---|
| **S1** | `core/network` + `core/auth` + Mock 数据源 | 能登录、能拿到假数据、拦截器链跑通 |
| **S2** | 一卡通三件套（余额/电费/空调费）+ Room + 电量页 | **第一个真正能用的功能** |
| **S3** | 番茄钟 + 前台服务 + 热力图 + 手动标签 | 第二个闭环 |
| **S4** | 课表 + 教学周 + 番茄钟联动 + 桌面小组件 | 核心体验 |
| **S5** | 图书馆预约记录（只读） | 总结更完整 |
| **S6** | 空教室（需先抓包） | 补齐 |
| **S7** | 周月年总结聚合 + 校园卡交易列表 | 收尾 |

**S1 的 Mock 层不是可选项**：一卡通交易、用电明细、空教室三处接口尚未定位，没有 Mock 就没有可运行的 App。
