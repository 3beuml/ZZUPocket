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
    ],
    version = 5,
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

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "zzupocket.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
                .also { instance = it }
        }
    }
}
