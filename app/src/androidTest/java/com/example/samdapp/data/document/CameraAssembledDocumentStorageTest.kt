package com.example.samdapp.data.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.samdapp.data.local.AppDatabase
import com.example.samdapp.data.local.document.AndroidDocumentCaptureStore
import com.example.samdapp.data.local.security.DocumentEncryptionProvider
import com.example.samdapp.data.repository.ConsultationDocumentRepositoryImpl
import com.example.samdapp.data.repository.ConsultationRepositoryImpl
import com.example.samdapp.data.repository.PatientRepositoryImpl
import com.example.samdapp.domain.document.DocumentBytes
import com.example.samdapp.domain.model.Consultation
import com.example.samdapp.domain.model.DepartmentCode
import com.example.samdapp.domain.model.DocumentSource
import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.model.RecordTypeCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * H-18, Build 3b (R9). A camera-assembled document must land in the SAME place, with the SAME row
 * shape, key scheme and retract semantics as a directly-uploaded one - there is one storage path,
 * not two. Run against a real Room database and the real Keystore for the same reason
 * `ConsultationVoiceUnconfirmedRefusalTest` is: the assertions that matter are about the persisted
 * row, and one of them is that no row exists at all.
 */
class CameraAssembledDocumentStorageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var store: AndroidDocumentCaptureStore
    private lateinit var repository: ConsultationDocumentRepositoryImpl
    private val sessions = mutableListOf<String>()

    private val consultationId = "consult-3b"
    // Deliberately shares no digit run with the ABHA number below: otherwise the "the ABHA
    // number never reaches a name" assertions would match the UHID and prove nothing.
    private val patientId = "PHCAAA000777"

    @Before
    fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val encryption = DocumentEncryptionProvider()
        store = AndroidDocumentCaptureStore(context, encryption)
        val consultationRepository = ConsultationRepositoryImpl(db.consultationDao(), db.attachmentDao())
        val patientRepository = PatientRepositoryImpl(db.patientDao())
        patientRepository.register(patient()).getOrThrow()
        consultationRepository.saveConsultation(consultation()).getOrThrow()
        repository = ConsultationDocumentRepositoryImpl(
            context, db.consultationDocumentDao(), consultationRepository, patientRepository, encryption, store,
        )
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        sessions.forEach { store.discardSession(it) }
        File(File(context.filesDir, "documents"), consultationId).deleteRecursively()
        db.close()
    }

    private fun patient() = Patient(
        id = patientId, fullName = "Test Patient", dateOfBirth = null, age = 40, biologicalSex = "Male",
        guardianOrSpouseName = null, guardianRelation = null, mobileNumber = null, aadhaarNumber = null,
        abhaNumber = "91-1234-5678-9012", village = null, block = null, district = null, state = null,
        pincode = null, category = null, maritalStatus = null, bloodGroup = null, emergencyContact = null,
        primaryCareClinicName = null, referringPhysicianName = null,
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )

    private fun consultation() = Consultation(
        id = consultationId, patientId = patientId, encounterId = "enc-3b", chiefComplaint = "Fever",
        onset = null, durationBucket = null, severityScore = null, aggravatingFactors = null,
        relievingFactors = null, impactOnDailyActivities = null, impactOnDailyActivitiesProvenance = null,
        relevantHistory = null, transcription = null, attachments = emptyList(),
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )

    private fun captureThreePages(): Pair<String, List<String>> = runBlocking {
        val sessionId = store.newSession().also { sessions += it }
        val pages = listOf(Color.RED, Color.GREEN, Color.BLUE).map { color ->
            val pageId = UUID.randomUUID().toString()
            val bitmap = Bitmap.createBitmap(900, 1200, Bitmap.Config.RGB_565).apply { eraseColor(color) }
            File(store.stagingPathFor(sessionId, pageId)).outputStream()
                .use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            store.ingestPage(sessionId, pageId).getOrThrow()
            pageId
        }
        sessionId to pages
    }

    /** The whole R9 contract in one pass: same row shape, same key schemes, correct provenance,
     *  and bytes that come back out through the same reader the Build 3a viewer uses. */
    @Test
    fun aCameraAssembledDocumentIsStoredAndReadBackThroughTheSharedPath() = runBlocking<Unit> {
        val (sessionId, pages) = captureThreePages()
        val assembled = store.assemble(sessionId, pages) { _, _ -> }.getOrThrow()

        val document = repository.upload(
            consultationId = consultationId,
            bytes = assembled,
            label = "Blood test 12 Aug",
            departmentCode = DepartmentCode.CARDIO,
            recordTypeCode = RecordTypeCode.LAB_REPORT,
            uploaderUserId = "worker-1",
            uploaderRole = "ASHA_WORKER",
        ).getOrThrow()

        // Provenance, the two fields that distinguish this path at all.
        assertEquals(DocumentSource.CAMERA_ASSEMBLED, document.source)
        assertEquals(3, document.pageCount)

        // The row, read back from the database rather than from the returned object.
        val persisted = db.consultationDocumentDao().getById(document.id)!!
        assertEquals(DocumentSource.CAMERA_ASSEMBLED, persisted.source)
        assertEquals(3, persisted.pageCount)
        assertEquals(patientId, persisted.patientId)
        assertEquals("application/pdf", persisted.mimeType)

        // Same key schemes as PATH A: a non-identifying storage key, and a canonical name keyed
        // on the local UHID with the national ABHA number deliberately absent from both.
        assertTrue(
            "storage key must follow <RecordType>_<epochMillis>_<uuid>.pdf: ${persisted.storageKey}",
            Regex("^LAB_REPORT_\\d+_[0-9a-f-]+\\.pdf$").matches(persisted.storageKey),
        )
        assertEquals(
            "${patientId}_CARDIO_${
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
                    .withZone(java.time.ZoneId.systemDefault()).format(persisted.uploadedAt)
            }_LAB_REPORT.pdf",
            persisted.canonicalName,
        )
        assertFalse("the ABHA number must never reach a name", persisted.canonicalName.contains("1234"))
        assertFalse("the ABHA number must never reach a name", persisted.canonicalName.contains("5678"))
        assertTrue("the local UHID is what names the record", persisted.canonicalName.startsWith(patientId))
        assertFalse("the ABHA number must never reach a path", persisted.storageKey.contains("1234"))
        assertFalse("the local UHID must never reach a path either", persisted.storageKey.contains(patientId))
        assertFalse("the worker's label must never reach a path", persisted.storageKey.contains("Blood"))

        // The bytes come back through the same reader the viewer uses, and they are the PDF.
        val out = ByteArrayOutputStream()
        repository.readDecrypted(document.id, out)
        val bytes = out.toByteArray()
        assertEquals(document.sizeBytes, bytes.size.toLong())
        assertEquals("%PDF-", String(bytes.copyOf(5)))
        val temp = File.createTempFile("view", ".pdf", context.cacheDir)
        try {
            temp.writeBytes(bytes)
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { assertEquals(3, it.pageCount) }
            }
        } finally {
            temp.delete()
        }

        // The capture session is torn down once the row is durable: no encrypted PHI is left
        // sitting outside the document store with nothing pointing at it.
        assertFalse(
            File(File(File(context.filesDir, "documents"), ".capture"), sessionId).exists(),
        )
    }

    /** R4's end state, asserted where it counts: after an aborted assembly there is no document
     *  row for the consultation at all. A shorter PDF is never the fallback, and neither is a row
     *  pointing at a partial file. */
    @Test
    fun anAbortedAssemblyLeavesNoDocumentRowAndNoStoredBytes() = runBlocking<Unit> {
        val (sessionId, pages) = captureThreePages()
        val victim = File(
            File(File(File(context.filesDir, "documents"), ".capture"), sessionId),
            "${pages[1]}.enc",
        )
        val corrupted = victim.readBytes().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        victim.writeBytes(corrupted)

        val assembly = store.assemble(sessionId, pages) { _, _ -> }

        assertTrue(assembly.isFailure)
        assertEquals(
            "an aborted assembly must leave the documents table untouched",
            emptyList<Any>(),
            db.consultationDocumentDao().observeForConsultation(consultationId).first(),
        )
        assertFalse(
            "no document bytes may be stored for an assembly that aborted",
            File(File(context.filesDir, "documents"), consultationId).listFiles()?.isNotEmpty() ?: false,
        )
    }

    /** A capture session whose assembled file has been swept out from under the caller (process
     *  death between done and send) must fail the upload rather than insert a row with no bytes. */
    @Test
    fun aVanishedCaptureSessionIsRefusedRatherThanStoredAsAnEmptyRow() = runBlocking<Unit> {
        val result = repository.upload(
            consultationId = consultationId,
            bytes = DocumentBytes.AssembledCapture("session-that-was-swept", 3, 1000L, "hash"),
            label = "",
            departmentCode = DepartmentCode.GEN_PHYS,
            recordTypeCode = RecordTypeCode.OTHER,
            uploaderUserId = "worker-1",
            uploaderRole = "ASHA_WORKER",
        )

        assertTrue(result.isFailure)
        assertEquals(
            emptyList<Any>(),
            db.consultationDocumentDao().observeForConsultation(consultationId).first(),
        )
    }

    /** Retract is Build 3a's, unchanged: the row survives with `retractedAt` set and the bytes go. */
    @Test
    fun retractingACameraAssembledDocumentUsesTheSameInsertOnlySemantics() = runBlocking<Unit> {
        val (sessionId, pages) = captureThreePages()
        val assembled = store.assemble(sessionId, pages) { _, _ -> }.getOrThrow()
        val document = repository.upload(
            consultationId = consultationId, bytes = assembled, label = "scan",
            departmentCode = DepartmentCode.ORTHO, recordTypeCode = RecordTypeCode.IMAGING,
            uploaderUserId = "worker-1", uploaderRole = "ASHA_WORKER",
        ).getOrThrow()
        val storedFile = File(File(File(context.filesDir, "documents"), consultationId), document.storageKey)
        assertTrue(storedFile.exists())

        assertTrue(repository.retract(document.id, "WRONG_PATIENT").isSuccess)

        val persisted = db.consultationDocumentDao().getById(document.id)!!
        assertNotNull("the metadata row is never deleted", persisted.retractedAt)
        assertEquals(3, persisted.pageCount)
        assertFalse("the encrypted bytes are deleted", storedFile.exists())
        assertNull(
            "a retracted document leaves the worker-facing list",
            db.consultationDocumentDao().observeForConsultation(consultationId).first().firstOrNull(),
        )
    }
}
