package com.zzupocket.app.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// ── 实体 ────────────────────────────────────────────────────────────────

/**
 * 电量读数。主键用天然复合键 (accountType, roomId, ts) —— 天然唯一、天然幂等，
 * 所以**不需要** UUID，导入备份时也不会重复。
 *
 * accountType 取值：`"light"` = 照明/寝室，`"ac"` = 空调。
 * 两者是**两套独立的电表**，房间号也不同（同一栋楼里照明在 unit 73、空调在 unit 74）。
 */
@Entity(tableName = "energy_readings", primaryKeys = ["accountType", "roomId", "ts"])
data class EnergyReadingEntity(
    val accountType: String,
    val roomId: String,
    val ts: Long,              // epoch millis (UTC)
    val kwh: Double,           // 允许负值：欠费时学校就返回负数，是正常业务数据
    val updatedAt: Long,
)

/** 单行设置表（id 恒为 0）。设置类数据在合并备份时**整体覆盖**，不参与逐条合并。 */
@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @androidx.room.PrimaryKey val id: Int = 0,
    val account: String? = null,            // 只记学号，不记密码
    // 列名沿用 electricRoomId（避免不必要的迁移），语义是"照明房间"
    @androidx.room.ColumnInfo(name = "electricRoomId") val lightRoomId: String? = null,
    val acRoomId: String? = null,
    val defaultCampusId: Int = 2,           // 主校区
    val lowBatteryThreshold: Double = 10.0,
    val updatedAt: Long = 0,
)

/** 通用 TTL 缓存（校区/楼栋/教室/座位等非时序数据） */
@Entity(tableName = "kv_cache")
data class KvCacheEntity(
    @androidx.room.PrimaryKey val key: String,
    val payload: String,
    val fetchedAt: Long,
    val ttlSeconds: Long,
)

// ── DAO ────────────────────────────────────────────────────────────────

@Dao
interface EnergyReadingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reading: EnergyReadingEntity)

    @Query("SELECT * FROM energy_readings WHERE accountType = :type AND roomId = :roomId ORDER BY ts DESC LIMIT 1")
    suspend fun latest(type: String, roomId: String): EnergyReadingEntity?

    @Query("SELECT * FROM energy_readings WHERE accountType = :type AND roomId = :roomId ORDER BY ts DESC LIMIT 1")
    fun observeLatest(type: String, roomId: String): Flow<EnergyReadingEntity?>

    @Query("SELECT * FROM energy_readings WHERE accountType = :type AND roomId = :roomId AND ts >= :since ORDER BY ts ASC")
    suspend fun since(type: String, roomId: String, since: Long): List<EnergyReadingEntity>

    @Query("SELECT COUNT(*) FROM energy_readings")
    suspend fun count(): Int

    @Query("SELECT * FROM energy_readings ORDER BY ts ASC")
    suspend fun all(): List<EnergyReadingEntity>

    /**
     * 最近一次读数用的房间号。
     * 用来在"用户还没设置房间"时，从**本机已有的读数**里反推他自己住的房间
     * ——这样从老版本升级上来的人不用重填，而全新安装时依然是空的。
     */
    @Query("SELECT roomId FROM energy_readings WHERE accountType = :type ORDER BY ts DESC LIMIT 1")
    suspend fun latestRoomId(type: String): String?
}

@Dao
interface AppSettingsDao {

    @Query("SELECT * FROM app_settings WHERE id = 0")
    suspend fun get(): AppSettingsEntity?

    @Query("SELECT * FROM app_settings WHERE id = 0")
    fun observe(): Flow<AppSettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: AppSettingsEntity)
}

@Dao
interface KvCacheDao {

    @Query("SELECT * FROM kv_cache WHERE key = :key")
    suspend fun get(key: String): KvCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: KvCacheEntity)

    @Query("DELETE FROM kv_cache WHERE key = :key")
    suspend fun remove(key: String)
}

// ── 数据库 ──────────────────────────────────────────────────────────────

@Database(
    entities = [
        EnergyReadingEntity::class,
        AppSettingsEntity::class,
        KvCacheEntity::class,
        SemesterEntity::class,
        CourseEntity::class,
        CourseSessionEntity::class,
        PeriodEntity::class,
        FocusTagEntity::class,
        FocusSessionEntity::class,
        CardTransactionEntity::class,
        TimetableSyncEntity::class,
        RoomOccupancyEntity::class,
        DisplayOverrideEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun energyDao(): EnergyReadingDao
    abstract fun settingsDao(): AppSettingsDao
    abstract fun kvCacheDao(): KvCacheDao
    abstract fun semesterDao(): SemesterDao
    abstract fun courseDao(): CourseDao
    abstract fun courseSessionDao(): CourseSessionDao
    abstract fun periodDao(): PeriodDao
    abstract fun focusTagDao(): FocusTagDao
    abstract fun focusSessionDao(): FocusSessionDao
    abstract fun cardTransactionDao(): CardTransactionDao
    abstract fun timetableSyncDao(): TimetableSyncDao
    abstract fun roomOccupancyDao(): RoomOccupancyDao
    abstract fun displayOverrideDao(): DisplayOverrideDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * v1 只有一个 accountType = "electric"（当时把空调房间当成了"电费"）。
         * v2 起区分为 "light" / "ac" 两套表计，所以把已有读数归到空调名下
         * —— 历史那些读数查的确实就是空调房间。**不删数据。**
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE energy_readings SET accountType = 'ac' WHERE accountType = 'electric'")
            }
        }

        /** v3 新增课表相关四张表（学期 / 课程 / 上课记录 / 节次）。全是新表，不动旧数据。 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `semesters` (" +
                        "`id` INTEGER NOT NULL, `code` TEXT NOT NULL, `nameZh` TEXT NOT NULL, " +
                        "`schoolYear` TEXT NOT NULL, `startDate` TEXT, `endDate` TEXT, " +
                        "`weekStartOnSunday` INTEGER NOT NULL, `weekIndicesCsv` TEXT NOT NULL, " +
                        "`isCurrent` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `courses` (" +
                        "`key` TEXT NOT NULL, `semesterId` INTEGER NOT NULL, `code` TEXT NOT NULL, " +
                        "`nameZh` TEXT NOT NULL, `credits` REAL NOT NULL, `openDepartment` TEXT, " +
                        "`teachersCsv` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `course_sessions` (" +
                        "`id` TEXT NOT NULL, `courseKey` TEXT NOT NULL, `semesterId` INTEGER NOT NULL, " +
                        "`source` TEXT NOT NULL, `courseName` TEXT NOT NULL, `weekIndex` INTEGER NOT NULL, " +
                        "`weekday` INTEGER NOT NULL, `startUnit` INTEGER NOT NULL, `endUnit` INTEGER NOT NULL, " +
                        "`date` TEXT, `startTime` TEXT, `endTime` TEXT, `teacherName` TEXT, " +
                        "`roomText` TEXT, `campusText` TEXT, `color` TEXT, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `periods` (" +
                        "`indexNo` INTEGER NOT NULL, `nameZh` TEXT NOT NULL, `startTime` INTEGER NOT NULL, " +
                        "`endTime` INTEGER NOT NULL, `dayPart` TEXT NOT NULL, `segmentIndex` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`indexNo`))",
                )
            }
        }

        /** v4 新增番茄钟两张表（标签 / 专注记录）。全是新表，不动旧数据。 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `focus_tags` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`colorArgb` INTEGER NOT NULL, `archived` INTEGER NOT NULL, " +
                        "`sortOrder` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `focus_sessions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tagId` INTEGER, " +
                        "`tagName` TEXT NOT NULL, `phase` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, " +
                        "`endedAt` INTEGER NOT NULL, `plannedSeconds` INTEGER NOT NULL, " +
                        "`actualSeconds` INTEGER NOT NULL, `completed` INTEGER NOT NULL, " +
                        "`sequenceInDay` INTEGER NOT NULL DEFAULT 0, `note` TEXT NOT NULL, " +
                        "`contextRef` TEXT NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_focus_sessions_startedAt` ON `focus_sessions` (`startedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_focus_sessions_tagId` ON `focus_sessions` (`tagId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_focus_sessions_contextRef` ON `focus_sessions` (`contextRef`)")
            }
        }

        /** v5 新增校园卡流水表（P4）。新表，不动旧数据。 */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `card_transactions` (" +
                        "`journo` TEXT NOT NULL, `summary` TEXT NOT NULL, " +
                        "`merchantName` TEXT NOT NULL, `dateText` TEXT NOT NULL, " +
                        "`ts` INTEGER NOT NULL, `amount` REAL NOT NULL, " +
                        "`isRefund` INTEGER NOT NULL, `tradeType` TEXT NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`journo`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_card_transactions_ts` " +
                        "ON `card_transactions` (`ts`)",
                )
            }
        }

        /**
         * v6 新增课表同步状态表（本科/研究生共用）。
         *
         * 为「先显示缓存、再后台刷新」打底：要知道缓存是什么时候抓的（12 小时 TTL、
         * 「数据截至 HH:MM」），以及上次为什么失败（区分会话过期与教务不可用）。
         * 新表，不动旧数据。
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `timetable_sync` (" +
                        "`source` TEXT NOT NULL, `semesterId` INTEGER NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL, `lastError` TEXT, `lastErrorAt` INTEGER, " +
                        "PRIMARY KEY(`source`, `semesterId`))",
                )
            }
        }

        /**
         * v7：课表同步表补两列（内容指纹 + 缓存版本），并新增空教室占用快照表。
         *
         * - `contentHash`：刷新后内容没变就"一个字都不动"，靠它判断；
         * - `cacheVersion`：结构版本，将来字段改了可直接丢弃旧缓存；
         * - `room_occupancy`：只缓存当天的教室占用（跨天即失效），见实体注释。
         */
        /**
         * v9：显示覆盖表（issue #6 保底）——用户对教务课程显示信息的本地自定义。
         * 全新表，不动旧数据。
         */
        /**
         * v9：显示覆盖表（issue #6 保底）——用户对教务课程显示信息的本地自定义。
         * 全新表，不动旧数据。
         *
         * ⚠️ 建表语句必须保持 v9 发布时的样子（**无 `weeks` 列**）：已经升到 v9 的
         * 设备不会重跑本迁移，而 v8 直升 v10 的设备要靠 8→9→10 链式执行——
         * 若这里建出带 `weeks` 的表，[MIGRATION_9_10] 的 ALTER 会因列已存在而炸。
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `display_overrides` (" +
                        "`id` TEXT NOT NULL, `semesterId` INTEGER NOT NULL, `courseCode` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `weekday` INTEGER, `startUnit` INTEGER, " +
                        "`value` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
            }
        }

        /**
         * v10：display_overrides 加 `weeks` 列（issue #6 单双周场景）——
         * null = 该时段全部周；"1,3,5" = 只作用这些教学周（单双周换教室分周覆盖）。
         * 纯加列，不动旧数据（旧覆盖全部是 null = 全部周，语义不变）。
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `display_overrides` ADD COLUMN `weeks` TEXT")
            }
        }

        /**
         * v8：course_sessions 加 `experiment` 列（issue #7）——print-data 补全的实验/见习排课
         * 要和 micro-server 主数据区分开。全是新列默认 0，不动旧数据。
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `course_sessions` ADD COLUMN `experiment` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `timetable_sync` ADD COLUMN `contentHash` TEXT")
                db.execSQL("ALTER TABLE `timetable_sync` ADD COLUMN `cacheVersion` INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `room_occupancy` (" +
                        "`date` TEXT NOT NULL, `roomId` INTEGER NOT NULL, `campusId` INTEGER NOT NULL, " +
                        "`code` TEXT NOT NULL, `buildingId` INTEGER NOT NULL, `buildingName` TEXT NOT NULL, " +
                        "`campusName` TEXT NOT NULL, `occupations` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`date`, `roomId`))",
                )
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "zzupocket.db",
            ).addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
            )
                .build()
                .also { instance = it }
        }
    }
}
