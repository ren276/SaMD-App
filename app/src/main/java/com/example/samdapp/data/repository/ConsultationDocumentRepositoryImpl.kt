package com.example.samdapp.data.repository

import android.content.Context
import android.net.Uri
import com.example.samdapp.data.local.dao.ConsultationDocumentDao
import com.example.samdapp.data.local.entity.ConsultationDocumentEntity
import com.example.samdapp.data.local.security.DocumentEncryptionProvider
import com.example.samdapp.domain.DataError
import com.example.samdapp.domain.document.DocumentTypeValidator
import com.example.samdapp.domain.model.ConsultationDocument
import com.example.samdapp.domain.model.DepartmentCode
import com.example.samdapp.domain.model.DocumentSource
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
) : ConsultationDocumentRepository {

    companion object {
        const val MAX_DOCUMENT_SIZE_BYTES = 20L * 1024 * 1024
    }

    private fun documentsDir(consultationId: String): File =
        File(File(context.filesDir, "documents"), consultationId).apply { mkdirs() }

    override suspend fun upload(
        consultationId: String,
        sourceUri: String,
        claimedMimeType: String?,
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
        val source = context.contentResolver.openInputStream(Uri.parse(sourceUri))
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
                val id = UUID.randomUUID().toString()
                val uploadedAt = Instant.now()
                val storageKey = "${sanitize(recordTypeCode.name)}_${uploadedAt.toEpochMilli()}_${UUID.randomUUID()}.${detected.extension}"
                val destFile = File(documentsDir(consultationId), storageKey)

                val encryptResult = encryptionProvider.encryptToFile(fullStream, destFile, MAX_DOCUMENT_SIZE_BYTES)

                val uhid = patient.id
                val canonicalName = listOf(
                    sanitize(uhid),
                    sanitize(departmentCode.name),
                    CANONICAL_DATE_FMT.format(uploadedAt),
                    sanitize(recordTypeCode.name),
                ).joinToString("_") + ".${detected.extension}"

                val document = ConsultationDocument(
                    id = id,
                    consultationId = consultationId,
                    patientId = consultation.patientId,
                    abhaNumber = patient.abhaNumber,
                    label = label,
                    canonicalName = canonicalName,
                    departmentCode = departmentCode,
                    recordTypeCode = recordTypeCode,
                    storageKey = storageKey,
                    mimeType = detected.mimeType,
                    sizeBytes = encryptResult.sizeBytes,
                    sha256 = encryptResult.sha256,
                    source = DocumentSource.DIRECT_FILE,
                    uploadedAt = uploadedAt,
                    uploaderUserId = uploaderUserId,
                    uploaderRole = uploaderRole,
                    retractedAt = null,
                    retractionReason = null,
                )
                // Encryption already succeeded and destFile exists on disk at this point — if the
                // metadata insert fails, the file must not linger with no row pointing at it
                // (the "failure paths write nothing" contract this method documents).
                try {
                    consultationDocumentDao.insert(document.toEntity(localModifiedAt = uploadedAt))
                } catch (e: Exception) {
                    destFile.delete()
                    throw e
                }
                document
            }
        }
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
    uploadedAt = uploadedAt,
    uploaderUserId = uploaderUserId,
    uploaderRole = uploaderRole,
    retractedAt = retractedAt,
    retractionReason = retractionReason,
)
