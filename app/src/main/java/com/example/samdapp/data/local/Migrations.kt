package com.example.samdapp.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `audit_log` (`id` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                "`userId` TEXT NOT NULL, `patientId` TEXT, `caseRecordId` TEXT, `action` TEXT NOT NULL, " +
                "`payload` TEXT NOT NULL, PRIMARY KEY(`id`))",
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_log_patientId` ON `audit_log` (`patientId`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_log_caseRecordId` ON `audit_log` (`caseRecordId`)")
    }
}
