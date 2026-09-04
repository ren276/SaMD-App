package com.example.samdapp.domain.repository

import com.example.samdapp.domain.model.ConsultationDocument
import com.example.samdapp.domain.model.DepartmentCode
import com.example.samdapp.domain.model.RecordTypeCode
import kotlinx.coroutines.flow.Flow
import java.io.OutputStream

/**
 * H-18, Build 3a. Unlike most repositories in this app, this one owns both the DB row AND the
 * encrypted file (mirrors [com.example.samdapp.data.local.security.DatabasePassphraseProvider]'s
 * layering: Keystore/file mechanics live in the data layer, not split out into a separate domain
 * abstraction, because the file and the row must stay consistent with each other).
 */
interface ConsultationDocumentRepository {
    /**
     * [sourceUri] is an opaque content URI string, resolved to bytes only here in the data layer
     * (same posture as [com.example.samdapp.domain.model.Attachment.uri] elsewhere in this app —
     * a `Uri` never travels through the ViewModel/use-case layers as a typed Android object).
     * Validates the resolved bytes by magic bytes (never [claimedMimeType], never a filename
     * extension), enforces the size cap while streaming, encrypts the plaintext to
     * `filesDir/documents/<consultationId>/`, and inserts the metadata row with `patientId`
     * derived from the consultation (never from a caller-supplied value). Failure paths (oversized,
     * unrecognised/mismatched type, unresolvable URI) write nothing — no partial row, no partial
     * file.
     */
    suspend fun upload(
        consultationId: String,
        sourceUri: String,
        claimedMimeType: String?,
        label: String,
        departmentCode: DepartmentCode,
        recordTypeCode: RecordTypeCode,
        uploaderUserId: String,
        uploaderRole: String,
    ): Result<ConsultationDocument>

    suspend fun getById(documentId: String): ConsultationDocument?

    /** Retracted rows excluded — the default worker-facing list. */
    fun observeForConsultation(consultationId: String): Flow<List<ConsultationDocument>>

    /** Audit/admin view only. */
    fun observeIncludingRetracted(consultationId: String): Flow<List<ConsultationDocument>>

    /** Decrypts the document's plaintext bytes into [output]. Throws
     *  [com.example.samdapp.data.local.security.DocumentDecryptionFailedException] on a corrupt
     *  or tampered file — callers must surface this as an explicit error, never a blank view. */
    suspend fun readDecrypted(documentId: String, output: OutputStream)

    /** Metadata row is never deleted — `retractedAt` is set and the encrypted bytes are deleted.
     *  Every other column stays intact so the retraction is legible later, not erased. */
    suspend fun retract(documentId: String, reason: String?): Result<Unit>
}
