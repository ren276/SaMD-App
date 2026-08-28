package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.PatientEntity
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface PatientDao {
    @Insert
    suspend fun insert(patient: PatientEntity)

    /** Phase 6b outbox: rows this device has never pushed, or has locally re-modified since its
     *  last push. Never `CONFLICT`/`FAILED`/`SYNCED` — see SyncOutboxRepository's KDoc. */
    @Query("SELECT * FROM patients WHERE syncState = 'PENDING' ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(): List<PatientEntity>

    /** [serverVersion] is `COALESCE`d against the existing value so a `conflict`/`rejected` ack
     *  (which carries no fresh version) never wipes the version from a prior successful sync. */
    @Query(
        "UPDATE patients SET syncState = :syncState, " +
            "serverVersion = COALESCE(:serverVersion, serverVersion), " +
            "syncErrorCode = :syncErrorCode, lastSyncAttemptAt = :attemptAt " +
            "WHERE id = :id AND localModifiedAt = :sentLocalModifiedAt",
    )
    suspend fun applySyncResult(id: String, syncState: SyncState, serverVersion: Int?, syncErrorCode: String?, attemptAt: Instant, sentLocalModifiedAt: Instant)

    @Query("SELECT COUNT(*) FROM patients WHERE syncState = 'FAILED'")
    fun observeFailedSyncCount(): Flow<Int>

    @Query("SELECT * FROM patients WHERE id = :patientId")
    fun observeById(patientId: String): Flow<PatientEntity?>

    /**
     * Patients with at least one encounter whose startedAt falls in [startMillis, endMillis).
     * Deliberately no "all patients" query exists on this DAO — the only list surface is
     * date-bounded, so no code path can pull the full patient table onto the device
     * (data-minimization, see agent_docs/hardening.md). Bounds are epoch-millis Longs
     * compared directly against the stored INTEGER column; the caller decides what window
     * "today" means.
     */
    @Query(
        "SELECT p.* FROM patients p " +
            "INNER JOIN encounters e ON e.patientId = p.id " +
            "WHERE e.startedAt >= :startMillis AND e.startedAt < :endMillis " +
            "GROUP BY p.id " +
            "ORDER BY MAX(e.startedAt) DESC",
    )
    fun observePatientsWithEncounterBetween(startMillis: Long, endMillis: Long): Flow<List<PatientEntity>>

    /**
     * Patients tab directory read: registered in the window OR seen (encounter started) in the
     * window, whichever is later. Deliberately a second, separate query from
     * [observePatientsWithEncounterBetween] rather than a shared one with a flag - Home's
     * work-queue semantics (REQ-ROS-01, encounter required) and this tab's directory semantics
     * (registration is enough) must never be able to drift onto the same query by accident.
     * `INNER JOIN encounters` above is the only encounter-required roster query on this DAO and
     * must stay that way (see scripts/check-single-inner-join-encounters.sh); this one is LEFT.
     *
     * The window predicate is in HAVING, never WHERE. A WHERE clause on `e.startedAt` would
     * silently re-narrow this LEFT JOIN back into an INNER JOIN (null-extended rows fail the
     * WHERE and get dropped), which compiles, runs, and reintroduces the exact bug this query
     * exists to fix: an encounter-less patient becomes unfindable again. HAVING runs after the
     * GROUP BY, so a patient with no encounter is judged on their own createdAt instead of
     * being dropped.
     *
     * The window-membership value is `MAX(p.createdAt, COALESCE(MAX(e.startedAt), p.createdAt))`,
     * not a plain `COALESCE` - a plain `COALESCE(MAX(e.startedAt), p.createdAt)` would pick the
     * encounter time whenever any encounter exists, even one earlier than registration, which
     * would wrongly exclude a patient registered inside the window whose only encounter (however
     * that came to exist) is outside it. Today's write order never produces that state (an
     * encounter can only be created for a patient that already exists, so its startedAt is
     * always >= that patient's createdAt) - the `MAX` makes the query correct without depending
     * on that being enforced anywhere. `lastSeenAt` itself stays the plain encounter aggregate
     * (null when there is none) - only the window-membership decision uses the wider value.
     *
     * Safe while sync is push-only (docs/sync-design.md: RemoteMediator/pull not built) - every
     * patients row on this device was authored on this device, so this cannot surface anyone
     * this worker did not personally register. When pull lands, this must additionally filter
     * to device-authored rows or it reopens REQ-ROS-02/H-04 for server-sourced patients.
     */
    @Query(
        "SELECT p.*, MAX(e.startedAt) AS lastSeenAt FROM patients p " +
            "LEFT JOIN encounters e ON e.patientId = p.id " +
            "GROUP BY p.id " +
            "HAVING MAX(p.createdAt, COALESCE(MAX(e.startedAt), p.createdAt)) >= :startMillis " +
            "AND MAX(p.createdAt, COALESCE(MAX(e.startedAt), p.createdAt)) < :endMillis " +
            "ORDER BY MAX(p.createdAt, COALESCE(MAX(e.startedAt), p.createdAt)) DESC, p.id ASC",
    )
    fun observeRegisteredOrSeenBetween(startMillis: Long, endMillis: Long): Flow<List<PatientDirectoryRow>>
}
