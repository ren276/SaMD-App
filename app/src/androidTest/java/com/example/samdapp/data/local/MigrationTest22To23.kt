package com.example.samdapp.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/**
 * [MIGRATION_22_23] against a real, SQLCipher-encrypted test database (never the app's own), the
 * same rationale as [MigrationTest21To22]. FS-3: the upgrade is tested by actually running it.
 *
 * The migration only adds `device_data_owner`, so the risk is that it touches something else.
 * Every v22 table is seeded with one row, generated from the table's own `PRAGMA table_info` so no
 * table is skipped, and every value of every row must come out unchanged. The new table must exist
 * and be empty: an upgraded phone has data and no owner (FS-4(a)).
 */
class MigrationTest22To23 {

    private val testDbName = "migration-22-23-test.db"
    private val testPassphrase = "migration-test-passphrase".toByteArray()

    init {
        System.loadLibrary("sqlcipher")
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        databaseClass = AppDatabase::class.java,
        specs = emptyList(),
        openFactory = SupportOpenHelperFactory(testPassphrase),
    )

    private fun SupportSQLiteDatabase.userTables(): List<String> =
        query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT IN ('android_metadata', 'room_master_table') ORDER BY name",
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /** One row per table, a value for every column by its declared type, so each column is seeded. */
    private fun SupportSQLiteDatabase.seedOneRow(table: String) {
        val columns = query("PRAGMA table_info(`$table`)").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name")) to c.getString(c.getColumnIndexOrThrow("type"))) }
        }
        val values: List<Any> = columns.map { (name, type) ->
            when (type.uppercase()) {
                "INTEGER" -> 7L
                "REAL" -> 0.5
                else -> "$table-$name"
            }
        }
        execSQL(
            "INSERT INTO `$table` (${columns.joinToString { "`${it.first}`" }}) VALUES (${columns.joinToString { "?" }})",
            values.toTypedArray<Any?>(),
        )
    }

    private fun SupportSQLiteDatabase.snapshot(table: String): List<List<String?>> =
        query("SELECT * FROM `$table` ORDER BY rowid").use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
        }

    private fun SupportSQLiteDatabase.count(table: String): Int =
        query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun migration22To23_keepsEveryRowOfEveryTableAndAddsAnEmptyOwnerTable() {
        val before: Map<String, List<List<String?>>>
        helper.createDatabase(testDbName, 22).apply {
            val tables = userTables()
            assertEquals("the v22 schema has 22 tables", 22, tables.size)
            assertTrue("the owner table is new in 23", "device_data_owner" !in tables)
            tables.forEach { seedOneRow(it) }
            before = tables.associateWith { snapshot(it) }
            close()
        }
        assertTrue("every table holds its seeded row", before.values.all { it.size == 1 })

        val migrated = try {
            helper.runMigrationsAndValidate(testDbName, 23, true, MIGRATION_22_23)
        } catch (e: IllegalStateException) {
            fail("MIGRATION_22_23 does not produce the exported v23 schema: ${e.message}")
            throw e
        }

        assertEquals("every v22 table is still there", before.keys + "device_data_owner", migrated.userTables().toSet())
        before.forEach { (table, rows) ->
            assertEquals("$table: every row and every value survives", rows, migrated.snapshot(table))
        }
        assertEquals("the owner table exists and is empty", 0, migrated.count("device_data_owner"))
        migrated.close()
    }
}
