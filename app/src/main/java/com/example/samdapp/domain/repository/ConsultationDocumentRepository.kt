package com.example.samdapp.domain.repository

import com.example.samdapp.domain.document.DocumentBytes
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
     * The single storage entry point for BOTH upload paths (Build 3b): [bytes] says where the
     * document's content came from, and everything after that is identical - the same
     * `<RecordTypeCode>_<epochMillis>_<uuid>.<ext>` storage key, the same
     * `<UHID>_<Dept>_<YYYYMMDD>_<RecordType>.<ext>` canonical name, the same metadata row with
     * `patientId` derived from the consultation (never from a caller-supplied value), the same
     * retract, and the same `DOCUMENT_UPLOADED` audit row emitted by the one use case above this.
     *
     * - [DocumentBytes.DirectFile] (PATH A): an opaque content URI string, resolved to bytes only
     *   here in the data layer (same posture as [com.example.samdapp.domain.model.Attachment.uri]
     *   elsewhere in this app - a `Uri` never travels through the ViewModel/use-case layers as a
     *   typed Android object). Validated by MAGIC BYTES, never by the claimed MIME type and never
     *   by a filename extension, with the size cap enforced while streaming.
     * - [DocumentBytes.AssembledCapture] (PATH B): an already-encrypted PDF this app assembled
     *   from camera captures, moved out of its capture-session directory into the document store.
     *   Its type needs no sniffing because this app produced it, and its plaintext size and
     *   SHA-256 were measured by the same encryption pass that wrote it.
     *
     * Failure paths (oversized, unrecognised/mismatched type, unresolvable URI, a capture session
     * whose assembled file is gone) write nothing - no partial row, no partial file.
     */
    suspend fun upload(
        consultationId: String,
        bytes: DocumentBytes,
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
