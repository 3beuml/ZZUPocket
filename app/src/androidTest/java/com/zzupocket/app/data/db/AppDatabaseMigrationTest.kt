package com.zzupocket.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 10 → 11 的真机迁移测试。
 *
 * 用 Room 导出的 10.json 建一个 v10 库，塞一条旧表数据，再跑正式迁移：
 * 旧数据必须保留，新表 news_articles 必须存在。
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate10To11KeepsOldRowsAndCreatesNewsTable() {
        val databaseName = "migration-10-11-test.db"
        helper.createDatabase(databaseName, 10).apply {
            execSQL(
                "INSERT INTO kv_cache (`key`, `payload`, `fetchedAt`, `ttlSeconds`) " +
                    "VALUES ('migration-probe', 'kept', 0, 0)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            databaseName,
            11,
            true,
            AppDatabase.MIGRATION_10_11,
        ).use { db ->
            db.query("SELECT `payload` FROM kv_cache WHERE `key` = 'migration-probe'").use { cursor ->
                assertTrue("旧表数据在迁移后应保留", cursor.moveToFirst())
                assertEquals("kept", cursor.getString(0))
            }
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'news_articles'").use { cursor ->
                assertTrue("迁移后应存在 news_articles 表", cursor.moveToFirst())
            }
        }
    }
}
