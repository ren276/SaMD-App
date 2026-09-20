package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.ConsultationDocumentEntity
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * H-18, Build 3a. The metadata row is never deleted or generically updated — retract is the one
 * exception, and (same reasoning as [AuditLogDao]'s KDoc for `applySyncResult`) it is not really
 * one: it only ever touches `retractedAt`/`retractionReason`/`localModifiedAt`, never the upload
 * content columns, so no method here can rewrite what was actually uploaded. Read queries filter
 * `retractedAt IS NULL` by default (same as [com.example.samdapp.data.local.dao.ReferralDao]'s
 * status-filtered reads); [observeIncludingRetracted] is the explicit audit/admin escape hatch.
 */
@Dao
interface ConsultationDocumentDao {
    @Insert
    suspend fun insert(document: ConsultationDocumentEntity)

    @Query("SELECT * FROM consultation_documents WHERE id = :id")
    suspend fun getById(id: String): ConsultationDocumentEntity?

    @Query("SELECT * FROM consultation_documents WHERE consultationId = :consultationId AND retractedAt IS NULL ORDER BY uploadedAt DESC")
    fun observeForConsultation(consultationId: String): Flow<List<ConsultationDocumentEntity>>

    /** Includes retracted rows — audit/admin view only, never the default worker-facing list. */
    @Query("SELECT * FROM consultation_documents WHERE consultationId = :consultationId ORDER BY uploadedAt DESC")
    fun observeIncludingRetracted(consultationId: String): Flow<List<ConsultationDocumentEntity>>

    /** The one retraction mutation this DAO exposes. Never touches any upload-content column. */
    @Query(
        "UPDATE consultation_documents SET retractedAt = :retractedAt, retractionReason = :reason, " +
            "localModifiedAt = :localModifiedAt WHERE id = :id",
    )
    suspend fun retract(id: String, retractedAt: Instant, reason: String?, localModifiedAt: Instant)

    /** Phase 6b outbox shape, matching every other syncable entity in this schema — present for
     *  the row's own consistency (the entity carries the standard sync block per the H-18 build
     *  brief) but **not wired into the sync-push sweep in Build 3a**: no backend table or endpoint
     *  for `consultation_documents` exists yet (memo B8: document-byte transport is a
     *  PRE-PRODUCTION GATE, not build-now). Registering this in the generic outbox before a
     *  backend accepts the row would fail exactly the way an unmirrored `AuditAction` would. */
    @Query("SELECT * FROM consultation_documents WHERE syncState = 'PENDING' ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(): List<ConsultationDocumentEntity>

    @Query(
        "UPDATE consultation_documents SET " +
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
        "UPDATE consultation_documents SET syncState = 'PENDING', syncAttemptCount = 0, " +
            "syncErrorCode = NULL, syncErrorMessage = NULL " +
            "WHERE id = :id AND syncState = 'FAILED'",
    )
    suspend fun requeueFailed(id: String)
}
