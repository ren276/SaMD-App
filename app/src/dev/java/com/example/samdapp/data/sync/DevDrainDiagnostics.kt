package com.example.samdapp.data.sync

import android.util.Log
import com.example.samdapp.data.local.AppDatabase
import com.example.samdapp.data.local.dao.SyncSql
import com.example.samdapp.data.remote.dto.SyncRecordDto
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dev-build-only drain diagnostics: at each drain start, every row the outbox still owes the
 * server (PENDING or RETRYABLE), with its sync metadata and whether the held fragment covers it,
 * then the ids the drain collected. Greppable by the tag [TAG]. Ids and sync metadata only, never
 * a clinical field. Lives only in `src/dev/`, so staging and prod cannot contain it
 * (`DrainDiagnosticsAbsenceTest`).
 *
 * Reads through the app's own open database inside the app process; nothing is copied off the
 * device.
 */
@Singleton
class DevDrainDiagnostics @Inject constructor(
    private val db: AppDatabase,
) : DrainObserver {

    /** table, id column, held fragment (null for a root table). */
    private val tables: List<Triple<String, String, String?>> = listOf(
        Triple("patients", "id", null),
        Triple("encounters", "id", SyncSql.HELD_ENCOUNTERS),
        Triple("consultations", "id", SyncSql.HELD_CONSULTATIONS),
        Triple("attachments", "id", SyncSql.HELD_ATTACHMENTS),
        Triple("observations", "id", SyncSql.HELD_OBSERVATIONS),
        Triple("ailments", "id", SyncSql.HELD_AILMENTS),
        Triple("medical_history_items", "id", SyncSql.HELD_MEDICAL_HISTORY_ITEMS),
        Triple("allergies", "id", SyncSql.HELD_ALLERGIES),
        Triple("family_history_entries", "id", SyncSql.HELD_FAMILY_HISTORY_ENTRIES),
        Triple("social_histories", "patientId", SyncSql.HELD_SOCIAL_HISTORIES),
        Triple("medication_entries", "id", SyncSql.HELD_MEDICATION_ENTRIES),
        Triple("case_records", "id", SyncSql.HELD_CASE_RECORDS),
        Triple("kernel_reports", "id", SyncSql.HELD_KERNEL_REPORTS),
        Triple("evaluate_reports", "id", SyncSql.HELD_EVALUATE_REPORTS),
        Triple("diagnosis_feedback", "id", SyncSql.HELD_DIAGNOSIS_FEEDBACK),
        Triple("prescriptions", "id", SyncSql.HELD_PRESCRIPTIONS),
        Triple("medication_lines", "id", SyncSql.HELD_MEDICATION_LINES),
        Triple("referrals", "id", SyncSql.HELD_REFERRALS),
        Triple("abha_profiles", "abhaId", null),
        Triple("audit_log", "id", null),
    )

    override suspend fun onDrainStart() = withContext(Dispatchers.IO) {
        Log.i(TAG, "drain start: rows owed to the server, per table")
        var total = 0
        for ((table, idColumn, held) in tables) {
            val heldExpr = held ?: "0"
            val sql = "SELECT $idColumn, syncState, syncAttemptCount, lastSyncAttemptAt, ($heldExpr) AS held " +
                "FROM $table WHERE syncState IN ('PENDING', 'RETRYABLE')"
            db.openHelper.readableDatabase.query(sql).use { cursor ->
                while (cursor.moveToNext()) {
                    total++
                    Log.i(
                        TAG,
                        "owed table=$table id=${cursor.getString(0)} state=${cursor.getString(1)} " +
                            "attempts=${cursor.getInt(2)} lastAttemptEpochMs=${if (cursor.isNull(3)) "null" else cursor.getLong(3)} " +
                            "held=${cursor.getInt(4) == 1}",
                    )
                }
            }
        }
        Log.i(TAG, "owed total=$total")
        // The one table whose drain query adds a predicate of its own: say which owed rows are
        // evaluate-failure markers (a failure code name, not clinical data).
        db.openHelper.readableDatabase.query(
            "SELECT id, failureCode FROM evaluate_reports WHERE syncState IN ('PENDING', 'RETRYABLE')",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                Log.i(TAG, "evaluate_reports id=${cursor.getString(0)} failureCode=${if (cursor.isNull(1)) "null" else cursor.getString(1)}")
            }
        }
        Unit
    }

    override fun onCollected(records: List<SyncRecordDto>) {
        Log.i(TAG, "collected count=${records.size}")
        records.forEach { Log.i(TAG, "collected table=${it.table} id=${it.id}") }
    }

    companion object {
        const val TAG = "DrainDiag"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DevDrainDiagnosticsModule {
    @Binds
    abstract fun bindDrainObserver(impl: DevDrainDiagnostics): DrainObserver
}
