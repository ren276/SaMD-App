package com.example.samdapp.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.samdapp.data.local.entity.AuditLogEntity
import com.example.samdapp.data.local.entity.CaseRecordEntity
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Async submission queue, carried ticket from PR #23 (STEP 1 design memo,
 * `scratchpad/casestatus-after-enqueue-design.md`): a case sent via `ConsultationViewModel.onSend`
 * stays at `CaseStatus.DRAFT` while its assessment is enqueued or running (`ConsultationScreen`
 * never advances the status). Both queries below must tell that case apart from a genuinely
 * in-progress `DRAFT` using the same signal, a `consultation_saved` audit row, since a real Room
 * DB is the only way to prove the `NOT EXISTS` correlation reads the right rows and does not
 * multiply them.
 */
class ResumableDraftAndAbandonDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = db.close()

    private fun caseRecord(id: String, patientId: String = "pat-1", updatedAt: Instant = Instant.EPOCH) =
        CaseRecordEntity(
            id = id, patientId = patientId, encounterId = "enc-$id", status = CaseStatus.DRAFT,
            assignedDoctorId = null, createdAt = Instant.EPOCH, updatedAt = updatedAt,
            localModifiedAt = Instant.EPOCH,
        )

    private fun auditRow(id: String, caseRecordId: String, action: String, userId: String = "worker-1", timestamp: Instant = Instant.EPOCH) =
        AuditLogEntity(
            id = id, timestamp = timestamp, userId = userId, patientId = null, caseRecordId = caseRecordId,
            action = action, payload = "{}", localModifiedAt = timestamp,
        )

    // --- observeResumableDraftForUser ---

    @Test
    fun submittedDraftIsNotResumable_butAGenuinelyInProgressDraftStillIs() = runBlocking {
        val caseDao = db.caseRecordDao()
        val auditDao = db.auditLogDao()

        // Submitted: encounter_started (this worker) + consultation_saved -> must NOT resume.
        caseDao.insert(caseRecord("case-submitted"))
        auditDao.insert(auditRow("a1", "case-submitted", "encounter_started"))
        auditDao.insert(auditRow("a2", "case-submitted", "consultation_saved"))

        // Genuinely in progress: encounter_started only -> must still resume.
        caseDao.insert(caseRecord("case-in-progress"))
        auditDao.insert(auditRow("a3", "case-in-progress", "encounter_started"))

        val resumable = caseDao.observeResumableDraftForUser("worker-1").first()

        assertEquals("case-in-progress", resumable?.id)
    }

    @Test
    fun onlySubmittedDraftExists_resumableIsNull() = runBlocking {
        val caseDao = db.caseRecordDao()
        val auditDao = db.auditLogDao()

        caseDao.insert(caseRecord("case-submitted"))
        auditDao.insert(auditRow("a1", "case-submitted", "encounter_started"))
        auditDao.insert(auditRow("a2", "case-submitted", "consultation_saved"))

        val resumable = caseDao.observeResumableDraftForUser("worker-1").first()

        assertNull(resumable)
    }

    // --- abandonDraftsForPatient ---

    @Test
    fun abandonDraftsForPatient_skipsASubmittedCase_butStillAbandonsAGenuinelyBackedOutDraft() = runBlocking {
        val caseDao = db.caseRecordDao()
        val auditDao = db.auditLogDao()

        // Submitted, multiple audit rows including a repeated consultation_saved-shaped action
        // to catch a JOIN-style row multiplication regression in the NOT EXISTS subquery.
        caseDao.insert(caseRecord("case-submitted", patientId = "pat-1"))
        auditDao.insert(auditRow("a1", "case-submitted", "encounter_started"))
        auditDao.insert(auditRow("a2", "case-submitted", "attachment_added"))
        auditDao.insert(auditRow("a3", "case-submitted", "consultation_saved"))

        // Genuinely backed out mid-flow, same patient: only encounter_started.
        caseDao.insert(caseRecord("case-backed-out", patientId = "pat-1"))
        auditDao.insert(auditRow("a4", "case-backed-out", "encounter_started"))

        caseDao.abandonDraftsForPatient("pat-1", Instant.ofEpochMilli(9000))

        val submitted = requireNotNull(caseDao.observeById("case-submitted").first())
        val backedOut = requireNotNull(caseDao.observeById("case-backed-out").first())
        assertEquals(CaseStatus.DRAFT, submitted.status)
        assertEquals(CaseStatus.ABANDONED, backedOut.status)
    }
}
