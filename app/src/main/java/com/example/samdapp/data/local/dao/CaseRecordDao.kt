package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.CaseRecordEntity
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface CaseRecordDao {
    @Insert
    suspend fun insert(caseRecord: CaseRecordEntity)

    /** Phase 6b outbox: this table's generic transport `syncState`, wholly distinct from the
     *  clinical `status` column (`PENDING_SYNC`/`SENT_TO_DOCTOR`/etc, see [observePendingSyncCount]
     *  above) this same table also carries. Draining a row here touches only `syncState`/
     *  `serverVersion`/`syncErrorCode`/`lastSyncAttemptAt` via [applySyncResult] below — never
     *  `status`. See PatientDao.getPendingForSync's KDoc for the general shape. */
    @Query("SELECT * FROM case_records WHERE " + SyncSql.PENDING_ELIGIBILITY_FRAGMENT + " AND NOT " + SyncSql.HELD_CASE_RECORDS + " "
            + "ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(retryEligibleBefore: Instant): List<CaseRecordEntity>

    @Query(
        "UPDATE case_records SET " +
        "syncState = CASE WHEN :syncState = 'RETRYABLE' AND syncAttemptCount + 1 >= :maxAttempts THEN 'FAILED' ELSE :syncState END, " +
        "serverVersion = COALESCE(:serverVersion, serverVersion), " +
        "syncErrorCode = CASE WHEN :syncState = 'RETRYABLE' AND syncAttemptCount + 1 >= :maxAttempts THEN :retryExhaustedCode ELSE :syncErrorCode END, " +
        "syncErrorMessage = :syncErrorMessage, " +
        "syncAttemptCount = CASE WHEN :syncState = 'SYNCED' THEN 0 ELSE syncAttemptCount + 1 END, " +
        "lastSyncAttemptAt = :attemptAt " +
        "WHERE id = :id AND localModifiedAt = :sentLocalModifiedAt",
    )
    suspend fun applySyncResult(id: String, syncState: SyncState, serverVersion: Int?, syncErrorCode: String?, syncErrorMessage: String?, attemptAt: Instant, sentLocalModifiedAt: Instant, maxAttempts: Int, retryExhaustedCode: String)

    /** The FAILED to PENDING transition, the only way out of a terminal state.
     *  Driven by an explicit human action (S-3 owns its UI): a worker who has fixed
     *  whatever the server objected to, or who knows the parent has since landed,
     *  asks for this row to be tried again.
     *
     *  Resets the attempt budget and clears both the code and the message, so the
     *  next failure is reported on its own terms rather than under the last one's.
     *  Guarded on FAILED so it cannot disturb a row that is mid-flight, and
     *  idempotent: pressing retry twice is one requeue. */
    @Query(
        "UPDATE case_records SET syncState = 'PENDING', syncAttemptCount = 0, " +
        "syncErrorCode = NULL, syncErrorMessage = NULL " +
        "WHERE id = :id AND syncState = 'FAILED'",
    )
    suspend fun requeueFailed(id: String)

    @Query("SELECT syncState AS syncState, " + SyncSql.HELD_CASE_RECORDS + " AS held, COUNT(*) AS rowCount FROM case_records WHERE syncState != 'SYNCED' GROUP BY syncState, held")
    fun observeSyncStateCounts(): Flow<List<SyncStateCount>>

    /** The same counts, read once (for a decision made right after a drain). */
    @Query("SELECT syncState AS syncState, " + SyncSql.HELD_CASE_RECORDS + " AS held, COUNT(*) AS rowCount FROM case_records WHERE syncState != 'SYNCED' GROUP BY syncState, held")
    suspend fun getSyncStateCounts(): List<SyncStateCount>

    /** The FAILED and CONFLICT rows of this table, projected for the worker-facing review list.
     *  Selects exactly the FAILED and CONFLICT groups of observeSyncStateCounts, so the number on the Home
     *  card and the length of the list can never disagree. Suspend rather than a Flow:
     *  the list is fetched when a worker opens it, so it costs nothing at launch.
     *  See [FailedSyncRow]. */
    @Query(
        "SELECT 'case_records' AS tableName, case_records.syncState AS syncState, case_records.id AS " +
        "recordId, case_records.patientId AS patientId, case_records.localModifiedAt AS recordedAt, " +
        "case_records.syncErrorCode AS syncErrorCode, case_records.syncErrorMessage AS syncErrorMessage, " +
        "case_records.serverVersion AS serverVersion, case_records.encounterId AS encounterId, CAST(NULL " +
        "AS TEXT) AS caseRecordId, CAST(NULL AS TEXT) AS parentId FROM case_records WHERE " +
        "case_records.syncState IN ('FAILED', 'CONFLICT') OR (case_records.syncState IN " +
        SyncSql.UNSENT_STATES + " AND " +
        SyncSql.HELD_CASE_RECORDS + ")",
    )
    suspend fun getFailedForReview(): List<FailedSyncRow>

    /** Also stamps `localModifiedAt` from the same [updatedAt] value, see MIGRATION_12_13's
     *  KDoc for why the two columns are deliberately redundant on entities that have both, and
     *  resets the transport `syncState` to `PENDING` in the same statement (syncstate-reset
     *  session) — `status` is part of the synced payload (CaseRecordSyncPayloadDto), so a status
     *  change on an already-`SYNCED` row must re-drain. `serverVersion` is left untouched. */
    @Query("UPDATE case_records SET status = :status, updatedAt = :updatedAt, localModifiedAt = :updatedAt, syncState = 'PENDING' WHERE id = :caseRecordId")
    suspend fun updateStatus(caseRecordId: String, status: CaseStatus, updatedAt: Instant)

    @Query(
        "UPDATE case_records SET status = :status, assignedDoctorId = :doctorId, updatedAt = :updatedAt, " +
        "localModifiedAt = :updatedAt, syncState = 'PENDING' WHERE id = :caseRecordId",
    )
    suspend fun assignDoctor(caseRecordId: String, doctorId: String, status: CaseStatus, updatedAt: Instant)

    /** Called right before a fresh [CaseRecordEntity] is inserted for [patientId] (see
     *  [com.example.samdapp.domain.usecase.StartCaseUseCase]) so an earlier attempt this worker
     *  backed out of mid-flow — still `DRAFT`, never reaching Acknowledgement — can't resurface via
     *  [observeResumableDraftForUser] and get confused with the visit that's actually in progress.
     *  Resets `syncState` to `PENDING` too (syncstate-reset session): the `ABANDONED` status is
     *  itself a synced field, and this bulk update can hit an already-`SYNCED` DRAFT row.
     *
     *  Excludes a `DRAFT` case that already has a `consultation_saved` audit row (async
     *  submission queue): that case was sent, and its assessment may already be enqueued or
     *  complete, so a returning patient's new visit must not relabel it `ABANDONED` out from
     *  under the queue. Same signal, same shape, as [observeResumableDraftForUser]'s exclusion. */
    @Query(
        "UPDATE case_records SET status = 'ABANDONED', updatedAt = :updatedAt, localModifiedAt = :updatedAt, " +
        "syncState = 'PENDING' WHERE patientId = :patientId AND status = 'DRAFT' " +
        "AND NOT EXISTS (SELECT 1 FROM audit_log al WHERE al.caseRecordId = case_records.id " +
        "AND al.action = 'consultation_saved')",
    )
    suspend fun abandonDraftsForPatient(patientId: String, updatedAt: Instant)

    /** "Sync Up": every locally-queued case (doctor already assigned, just waiting for network)
     *  moves to `SENT_TO_DOCTOR` in one round. Resets `syncState` to `PENDING` too
     *  (syncstate-reset session), for the same reason as [updateStatus]. */
    @Query(
        "UPDATE case_records SET status = 'SENT_TO_DOCTOR', updatedAt = :updatedAt, localModifiedAt = :updatedAt, " +
        "syncState = 'PENDING' WHERE status = 'PENDING_SYNC'",
    )
    suspend fun sendAllPendingSync(updatedAt: Instant)

    @Query("SELECT COUNT(*) FROM case_records WHERE status = 'PENDING_SYNC'")
    fun observePendingSyncCount(): Flow<Int>

    @Query("SELECT * FROM case_records WHERE id = :caseRecordId")
    fun observeById(caseRecordId: String): Flow<CaseRecordEntity?>

    /** The day-ordinal queue-position receipt (async submission queue, "case N of today") -
     *  display-only, never a lookup key. Bounded by the day window the caller computes from the
     *  target case's OWN `createdAt` (not the clock at read time - see
     *  [com.example.samdapp.data.repository.CaseRecordRepositoryImpl.getDayOrdinal]), with a
     *  `(createdAt, id)` tiebreak mirroring [PatientDao.observeRegisteredOrSeenBetween]'s
     *  deterministic ordering. Stable: `case_records` rows are only ever inserted, never deleted
     *  (abandonment is a status flip to `ABANDONED`, see [abandonDraftsForPatient]), so the count
     *  of cases at or before a given one inside its day can never change once that case exists. */
    @Query(
        "SELECT COUNT(*) FROM case_records " +
        "WHERE createdAt >= :dayStartMillis AND createdAt < :dayEndMillis " +
        "AND (createdAt < :caseCreatedAtMillis OR (createdAt = :caseCreatedAtMillis AND id <= :caseRecordId))",
    )
    suspend fun getDayOrdinal(
        dayStartMillis: Long,
        dayEndMillis: Long,
        caseCreatedAtMillis: Long,
        caseRecordId: String,
    ): Int

    @Query("SELECT * FROM case_records WHERE patientId = :patientId ORDER BY updatedAt DESC LIMIT 1")
    fun observeLatestForPatient(patientId: String): Flow<CaseRecordEntity?>

    /** For doctor-continuity resolution (Part B): the case record belonging to a specific PRIOR
     *  encounter, so its [CaseRecordEntity.assignedDoctorId] can become the default for a
     *  follow-up encounter. */
    @Query("SELECT * FROM case_records WHERE encounterId = :encounterId LIMIT 1")
    fun observeByEncounterId(encounterId: String): Flow<CaseRecordEntity?>

    /** Crash-recovery resume (item 5, privacy/UX hardening pass): the current worker's own
     *  in-progress visit, if any — a case still `DRAFT` (never reached Acknowledgement/save),
     *  not yet submitted (no `consultation_saved` audit row — the async submission queue leaves
     *  a sent case at `DRAFT` while its assessment is enqueued or running, and that case must
     *  not be offered back as resumable), that THIS worker started, identified via the audit
     *  trail's `encounter_started` row rather than a new schema column (no `workerId` exists on
     *  `case_records`/`encounters`). One row max in practice (a worker starts a new visit only
     *  after finishing or abandoning the last), but `updatedAt DESC LIMIT 1` picks the most
     *  recent if more than one somehow exists.
     *
     *  The submitted-check is a `NOT EXISTS` subquery, not a second `JOIN` on `audit_log`: a
     *  join would multiply one row per matching `consultation_saved` entry and break the
     *  `LIMIT 1`/most-recent semantics above. */
    @Query(
        "SELECT cr.* FROM case_records cr " +
        "JOIN audit_log al ON al.caseRecordId = cr.id " +
        "WHERE al.userId = :userId AND al.action = 'encounter_started' AND cr.status = 'DRAFT' " +
        "AND NOT EXISTS (SELECT 1 FROM audit_log sent WHERE sent.caseRecordId = cr.id " +
        "AND sent.action = 'consultation_saved') " +
        "ORDER BY cr.updatedAt DESC LIMIT 1",
    )
    fun observeResumableDraftForUser(userId: String): Flow<CaseRecordEntity?>

    /** Count of this doctor's currently-open (sent, not yet reviewed) cases — the least-busy
     *  signal for auto-assigning a fresh/unrelated case (Part B). */
    @Query("SELECT COUNT(*) FROM case_records WHERE assignedDoctorId = :doctorId AND status = 'SENT_TO_DOCTOR'")
    fun observeOpenCaseCount(doctorId: String): Flow<Int>

    /** Cross-patient doctor-tracker rows (Part B's DoctorList): every case currently sent to or
     *  reviewed by a doctor, joined with the patient's name and the consultation's chief
     *  complaint. Deliberately not scoped to one patient — this IS the cross-patient view the
     *  tracker exists to show, unlike [com.example.samdapp.data.local.dao.PatientDao]'s
     *  deliberately day-scoped roster query. */
    @Query(
        "SELECT cr.id AS caseRecordId, cr.patientId AS patientId, cr.status AS status, " +
        "cr.updatedAt AS updatedAt, p.fullName AS patientFullName, c.chiefComplaint AS chiefComplaint, " +
        "d.name AS doctorName, d.specialty AS doctorSpecialty, " +
        "cr.syncState AS caseSyncState, cr.serverVersion AS caseServerVersion " +
        "FROM case_records cr " +
        "JOIN patients p ON p.id = cr.patientId " +
        "LEFT JOIN consultations c ON c.encounterId = cr.encounterId " +
        "LEFT JOIN doctors d ON d.id = cr.assignedDoctorId " +
        "WHERE cr.status IN ('SENT_TO_DOCTOR', 'PRESCRIPTION_RECEIVED') " +
        "ORDER BY cr.updatedAt DESC",
    )
    fun observeDoctorTrackerRows(): Flow<List<DoctorTrackerRow>>

    /** One case's sync chain for the assess gate: the case record LEFT JOINed to its encounter and
     *  patient, so a parent missing on this phone does not hide the case. Null when the case
     *  record itself is not here. */
    @Query(
        "SELECT c.syncState AS caseSyncState, c.serverVersion AS caseServerVersion, " +
            "c.syncErrorCode AS caseSyncErrorCode, c.syncErrorMessage AS caseSyncErrorMessage, " +
            "e.syncState AS encounterSyncState, e.serverVersion AS encounterServerVersion, " +
            "e.syncErrorCode AS encounterSyncErrorCode, e.syncErrorMessage AS encounterSyncErrorMessage, " +
            "p.syncState AS patientSyncState, p.serverVersion AS patientServerVersion, " +
            "p.syncErrorCode AS patientSyncErrorCode, p.syncErrorMessage AS patientSyncErrorMessage " +
            "FROM case_records c LEFT JOIN encounters e ON e.id = c.encounterId " +
            "LEFT JOIN patients p ON p.id = c.patientId WHERE c.id = :caseRecordId",
    )
    suspend fun getAssessGateRow(caseRecordId: String): AssessGateRow?
}
