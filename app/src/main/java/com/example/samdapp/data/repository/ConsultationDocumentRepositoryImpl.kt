package com.example.samdapp.data.repository

import android.content.Context
import android.net.Uri
import com.example.samdapp.data.local.dao.ConsultationDocumentDao
import com.example.samdapp.data.local.entity.ConsultationDocumentEntity
import com.example.samdapp.data.local.document.AndroidDocumentCaptureStore
import com.example.samdapp.data.local.security.DocumentEncryptionProvider
import com.example.samdapp.domain.DataError
import com.example.samdapp.domain.document.DocumentBytes
import com.example.samdapp.domain.document.DocumentTypeValidator
import com.example.samdapp.domain.document.ValidatedDocumentType
import com.example.samdapp.domain.model.ConsultationDocument
import com.example.samdapp.domain.model.DepartmentCode
import com.example.samdapp.domain.model.DocumentSource
import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.model.RecordTypeCode
import com.example.samdapp.domain.repository.ConsultationDocumentRepository
import com.example.samdapp.domain.repository.ConsultationRepository
import com.example.samdapp.domain.repository.PatientRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import java.io.SequenceInputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

private val CANONICAL_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.systemDefault())

/** Same sanitiser [com.example.samdapp.presentation.report.ReportPdfExporter] uses for its
 *  filename. Every canonical-name/storage-key component here is already system-generated
 *  (UHID, an enum name, a digit-only date, `epochMillis`, a UUID) so nothing free-text ever
 *  reaches either name — this is defense-in-depth, not a fix for an actual free-text leak. */
private fun sanitize(s: String): String = s.replace(Regex("[^A-Za-z0-9_-]"), "_")

class ConsultationDocumentRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val consultationDocumentDao: ConsultationDocumentDao,
    private val consultationRepository: ConsultationRepository,
    private val patientRepository: PatientRepository,
    private val encryptionProvider: DocumentEncryptionProvider,
    private val captureStore: AndroidDocumentCaptureStore,
) : ConsultationDocumentRepository {

    companion object {
        const val MAX_DOCUMENT_SIZE_BYTES = 20L * 1024 * 1024
    }

    private fun documentsDir(consultationId: String): File =
        File(File(context.filesDir, "documents"), consultationId).apply { mkdirs() }

    override suspend fun upload(
        consultationId: String,
        bytes: DocumentBytes,
        label: String,
        departmentCode: DepartmentCode,
        recordTypeCode: RecordTypeCode,
        uploaderUserId: String,
        uploaderRole: String,
    ): Result<ConsultationDocument> {
        val consultation = consultationRepository.getById(consultationId)
            ?: return Result.failure(DataError.NotFound("Consultation"))
        val patient = patientRepository.observePatient(consultation.patientId).first()
            ?: return Result.failure(DataError.NotFound("Patient"))
        // The ONLY branch between the two upload paths. Everything downstream of it - naming,
        // encryption destination, the metadata row, the insert-or-roll-back - is shared code.
        return when (bytes) {
            is DocumentBytes.DirectFile -> uploadDirectFile(
                bytes, consultation.patientId, consultationId, patient, label, departmentCode,
                recordTypeCode, uploaderUserId, uploaderRole,
            )
            is DocumentBytes.AssembledCapture -> storeAssembledCapture(
                bytes, consultation.patientId, consultationId, patient, label, departmentCode,
                recordTypeCode, uploaderUserId, uploaderRole,
            )
        }
    }

    private suspend fun uploadDirectFile(
        bytes: DocumentBytes.DirectFile,
        patientId: String,
        consultationId: String,
        patient: Patient,
        label: String,
        departmentCode: DepartmentCode,
        recordTypeCode: RecordTypeCode,
        uploaderUserId: String,
        uploaderRole: String,
    ): Result<ConsultationDocument> {
        val claimedMimeType = bytes.claimedMimeType
        val source = context.contentResolver.openInputStream(Uri.parse(bytes.sourceUri))
            ?: return Result.failure(DataError.NotFound("Source file"))

        return source.use {
            // Peek the header for magic-byte detection without losing those bytes for encryption —
            // ContentResolver streams are not guaranteed to support mark/reset, so read explicitly
            // and stitch the header back onto the stream rather than relying on it.
            val header = ByteArray(DocumentTypeValidator.REQUIRED_HEADER_BYTES)
            var headerRead = 0
            while (headerRead < header.size) {
                val n = source.read(header, headerRead, header.size - headerRead)
                if (n == -1) break
                headerRead += n
            }
            val detected = DocumentTypeValidator.detect(header.copyOf(headerRead))
            if (detected == null) {
                return@use Result.failure(
                    DataError.Refused(
                        reason = "magic-byte detection failed for claimed type $claimedMimeType",
                        message = "Unsupported file type. Only PDF, JPEG, and PNG documents can be uploaded.",
                    ),
                )
            }
            if (claimedMimeType != null && claimedMimeType != detected.mimeType) {
                return@use Result.failure(
                    DataError.Refused(
                        reason = "claimed mimeType $claimedMimeType does not match detected ${detected.mimeType}",
                        message = "This file's contents do not match its reported type and were rejected.",
                    ),
                )
            }
            val fullStream = SequenceInputStream(ByteArrayInputStream(header, 0, headerRead), source)

            asDataResult {
                val uploadedAt = Instant.now()
                val storageKey = storageKeyFor(recordTypeCode, uploadedAt, detected.extension)
                val destFile = File(documentsDir(consultationId), storageKey)

                val encryptResult = encryptionProvider.encryptToFile(fullStream, destFile, MAX_DOCUMENT_SIZE_BYTES)

                val document = buildDocument(
                    consultationId = consultationId,
                    patientId = patientId,
                    patient = patient,
                    label = label,
                    departmentCode = departmentCode,
                    recordTypeCode = recordTypeCode,
                    storageKey = storageKey,
                    extension = detected.extension,
                    mimeType = detected.mimeType,
                    sizeBytes = encryptResult.sizeBytes,
                    sha256 = encryptResult.sha256,
                    source = DocumentSource.DIRECT_FILE,
                    pageCount = null,
                    uploadedAt = uploadedAt,
                    uploaderUserId = uploaderUserId,
                    uploaderRole = uploaderRole,
                )
                insertOrRollBack(document, destFile, uploadedAt)
            }
        }
    }

    /**
     * PATH B (Build 3b). The bytes are already encrypted, already a PDF, and already measured -
     * the capture store produced them under the same Keystore key this repository encrypts with -
     * so this path adds no second encryption and no second validation: it moves the finished file
     * into the document store under a storage key from the SAME scheme and inserts a row of the
     * SAME shape, differing only in `source` and `pageCount`.
     *
     * The rename is attempted first and falls back to copy-then-delete: both paths are under
     * `filesDir` so a rename normally succeeds, but a rename that silently failed would leave a
     * row pointing at nothing.
     */
    private suspend fun storeAssembledCapture(
        bytes: DocumentBytes.AssembledCapture,
        patientId: String,
        consultationId: String,
        patient: Patient,
        label: String,
        departmentCode: DepartmentCode,
        recordTypeCode: RecordTypeCode,
        uploaderUserId: String,
        uploaderRole: String,
    ): Result<ConsultationDocument> {
        val assembled = captureStore.assembledFile(bytes.captureSessionId)
        if (!assembled.isFile) return Result.failure(DataError.NotFound("Assembled document"))
        return asDataResult {
            val uploadedAt = Instant.now()
            val extension = ValidatedDocumentType.PDF.extension
            val storageKey = storageKeyFor(recordTypeCode, uploadedAt, extension)
            val destFile = File(documentsDir(consultationId), storageKey)

            if (!assembled.renameTo(destFile)) {
                assembled.copyTo(destFile, overwrite = true)
                assembled.delete()
            }

            val document = buildDocument(
                consultationId = consultationId,
                patientId = patientId,
                patient = patient,
                label = label,
                departmentCode = departmentCode,
                recordTypeCode = recordTypeCode,
                storageKey = storageKey,
                extension = extension,
                mimeType = ValidatedDocumentType.PDF.mimeType,
                sizeBytes = bytes.sizeBytes,
                sha256 = bytes.sha256,
                source = DocumentSource.CAMERA_ASSEMBLED,
                pageCount = bytes.pageCount,
                uploadedAt = uploadedAt,
                uploaderUserId = uploaderUserId,
                uploaderRole = uploaderRole,
            )
            val stored = insertOrRollBack(document, destFile, uploadedAt)
            // Only once the row is durably inserted is the capture session torn down. If the
            // insert threw, the session survives with its assembled file already moved away -
            // the startup sweep collects it, and no half-stored document exists either way.
            captureStore.discardSession(bytes.captureSessionId)
            stored
        }
    }

    private fun storageKeyFor(recordTypeCode: RecordTypeCode, uploadedAt: Instant, extension: String): String =
        "${sanitize(recordTypeCode.name)}_${uploadedAt.toEpochMilli()}_${UUID.randomUUID()}.$extension"

    @Suppress("LongParameterList")
    private fun buildDocument(
        consultationId: String,
        patientId: String,
        patient: Patient,
        label: String,
        departmentCode: DepartmentCode,
        recordTypeCode: RecordTypeCode,
        storageKey: String,
        extension: String,
        mimeType: String,
        sizeBytes: Long,
        sha256: String,
        source: DocumentSource,
        pageCount: Int?,
        uploadedAt: Instant,
        uploaderUserId: String,
        uploaderRole: String,
    ): ConsultationDocument {
        // `Patient.id` (the local UHID), never `abhaNumber` - the national identifier is
        // deliberately kept out of every filename. Shared by both paths so neither can drift.
        val canonicalName = listOf(
            sanitize(patient.id),
            sanitize(departmentCode.name),
            CANONICAL_DATE_FMT.format(uploadedAt),
            sanitize(recordTypeCode.name),
        ).joinToString("_") + ".$extension"
        return ConsultationDocument(
            id = UUID.randomUUID().toString(),
            consultationId = consultationId,
            patientId = patientId,
            abhaNumber = patient.abhaNumber,
            label = label,
            canonicalName = canonicalName,
            departmentCode = departmentCode,
            recordTypeCode = recordTypeCode,
            storageKey = storageKey,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            sha256 = sha256,
            source = source,
            pageCount = pageCount,
            uploadedAt = uploadedAt,
            uploaderUserId = uploaderUserId,
            uploaderRole = uploaderRole,
            retractedAt = null,
            retractionReason = null,
        )
    }

    /** The encrypted bytes are already on disk at this point - if the metadata insert fails, the
     *  file must not linger with no row pointing at it (the "failure paths write nothing"
     *  contract [upload] documents). */
    private suspend fun insertOrRollBack(
        document: ConsultationDocument,
        destFile: File,
        uploadedAt: Instant,
    ): ConsultationDocument {
        try {
            consultationDocumentDao.insert(document.toEntity(localModifiedAt = uploadedAt))
        } catch (e: Exception) {
            destFile.delete()
            throw e
        }
        return document
    }

    override suspend fun getById(documentId: String): ConsultationDocument? =
        consultationDocumentDao.getById(documentId)?.toDomain()

    override fun observeForConsultation(consultationId: String): Flow<List<ConsultationDocument>> =
        consultationDocumentDao.observeForConsultation(consultationId).map { list -> list.map { it.toDomain() } }

    override fun observeIncludingRetracted(consultationId: String): Flow<List<ConsultationDocument>> =
        consultationDocumentDao.observeIncludingRetracted(consultationId).map { list -> list.map { it.toDomain() } }

    override suspend fun readDecrypted(documentId: String, output: OutputStream) {
        val entity = consultationDocumentDao.getById(documentId)
            ?: throw IllegalArgumentException("No document $documentId")
        val file = File(documentsDir(entity.consultationId), entity.storageKey)
        encryptionProvider.decryptToStream(file, output)
    }

    override suspend fun retract(documentId: String, reason: String?): Result<Unit> {
        val entity = consultationDocumentDao.getById(documentId)
            ?: return Result.failure(DataError.NotFound("Document"))
        return asDataResult {
            // The metadata row is retracted FIRST, deliberately, before the file is touched: the
            // row is the record of truth. If this write throws, asDataResult reports failure and
            // the file is untouched (row still active, content still present — a consistent
            // state). Only once the row is durably marked retracted do we attempt to delete the
            // bytes; File.delete() on flash storage is not physical erasure — adequate here only
            // because the plaintext never touched disk (H-18 risk entry) — and is best-effort: a
            // delete failure leaves a correctly-retracted row pointing at bytes nothing will
            // serve again (readDecrypted/observeForConsultation both already exclude retracted
            // rows), rather than an active row with no content.
            consultationDocumentDao.retract(documentId, Instant.now(), reason, Instant.now())
            val file = File(documentsDir(entity.consultationId), entity.storageKey)
            file.delete()
        }
    }
}

private fun ConsultationDocument.toEntity(localModifiedAt: Instant) = ConsultationDocumentEntity(
    id = id,
    consultationId = consultationId,
    patientId = patientId,
    abhaNumber = abhaNumber,
    label = label,
    canonicalName = canonicalName,
    departmentCode = departmentCode,
    recordTypeCode = recordTypeCode,
    storageKey = storageKey,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    sha256 = sha256,
    source = source,
    pageCount = pageCount,
    uploadedAt = uploadedAt,
    uploaderUserId = uploaderUserId,
    uploaderRole = uploaderRole,
    retractedAt = retractedAt,
    retractionReason = retractionReason,
    localModifiedAt = localModifiedAt,
)

private fun ConsultationDocumentEntity.toDomain() = ConsultationDocument(
    id = id,
    consultationId = consultationId,
    patientId = patientId,
    abhaNumber = abhaNumber,
    label = label,
    canonicalName = canonicalName,
    departmentCode = departmentCode,
    recordTypeCode = recordTypeCode,
    storageKey = storageKey,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    sha256 = sha256,
    source = source,
    pageCount = pageCount,
    uploadedAt = uploadedAt,
    uploaderUserId = uploaderUserId,
    uploaderRole = uploaderRole,
    retractedAt = retractedAt,
    retractionReason = retractionReason,
)
