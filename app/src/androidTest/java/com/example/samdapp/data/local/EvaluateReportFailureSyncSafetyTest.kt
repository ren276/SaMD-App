package com.example.samdapp.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.samdapp.data.local.entity.EvaluateReportEntity
import com.example.samdapp.data.repository.EvaluateReportRepositoryImpl
import com.example.samdapp.domain.model.EvaluateBrandMapping
import com.example.samdapp.domain.model.EvaluateDiagnosticSummary
import com.example.samdapp.domain.model.EvaluateNlemTreatment
import com.example.samdapp.domain.model.EvaluateReportOutput
import com.example.samdapp.domain.model.EvaluateSafetyAndTriage
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * H-14 (docs/quality/h-14-evaluate-failure-decision.md, Option 2): the whole safety property of
 * a persisted evaluate-failure marker rests on one SQL predicate,
 * [com.example.samdapp.data.local.dao.EvaluateReportDao.getPendingForSync]'s
 * `failureCode IS NULL`. [RoomSyncOutboxRepository] pushes exactly what that query returns with
 * no further filtering, so this predicate is the actual, only enforcement point for "a failed
 * evaluate must never be pushable to the backend as a real report" — worth an explicit assertion
 * against a real Room database, not just design intent. Also covers the retry path
 * ([EvaluateReportRepositoryImpl.save]/[saveFailure] resolving the same row by `caseRecordId` via
 * `getIdForCase`), which the marker's clearing depends on just as much as the predicate does.
 */
class EvaluateReportFailureSyncSafetyTest {

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

    private fun evaluateOutput(caseRecordId: String) = EvaluateReportOutput(
        id = UUID.randomUUID().toString(),
        caseRecordId = caseRecordId,
        diagnosticSummary = EvaluateDiagnosticSummary(
            primaryIcdCandidate = "J11", primaryAilmentName = "Viral fever", differential = emptyList(),
        ),
        nlemTreatment = EvaluateNlemTreatment(
            recommendedDrug = "Paracetamol", levelOfHealthcare = listOf("PHC"), availableAtPHC = true,
            dosageForms = listOf("Tablet"), pediatricDose = null, citation = null, confidence = null,
            referralReason = null, matchedDisease = null,
        ),
        brandMapping = EvaluateBrandMapping(
            genericName = "Paracetamol", janAushadhiBrand = null, commercialBrands = emptyList(),
            brandMappingAvailable = false,
        ),
        safetyAndTriage = EvaluateSafetyAndTriage(
            vitalsTriage = null, requiresHumanReview = false, pediatricReferralFlag = false, failureReason = null,
        ),
        topIndianBrand = null,
        inferenceStartedAt = Instant.EPOCH,
        inferenceEndedAt = Instant.EPOCH,
    )

    @Test
    fun getPendingForSync_excludesAFailureRow_soItCanNeverBePushedToTheBackendAsARealReport() = runBlocking {
        val dao = db.evaluateReportDao()
        dao.upsert(
            EvaluateReportEntity(
                id = "ev-real", caseRecordId = "case-real", payloadJson = "{\"real\":true}",
                inferenceStartedAt = Instant.EPOCH, inferenceEndedAt = Instant.EPOCH,
                failureCode = null, syncState = SyncState.PENDING, localModifiedAt = Instant.EPOCH,
            ),
        )
        dao.upsert(
            EvaluateReportEntity(
                id = "ev-failed", caseRecordId = "case-failed", payloadJson = "{}",
                inferenceStartedAt = Instant.EPOCH, inferenceEndedAt = Instant.EPOCH,
                failureCode = "IOException", syncState = SyncState.PENDING, localModifiedAt = Instant.EPOCH,
            ),
        )

        val pending = dao.getPendingForSync()

        assertEquals(
            "only the real report may reach the outbox; the failure row must never be pushable",
            setOf("ev-real"),
            pending.map { it.id }.toSet(),
        )
    }

    @Test
    fun aSuccessfulRetryClearsTheFailureMarker_viaTheSameRow_noLeftoverDivergentRow() = runBlocking {
        val repo = EvaluateReportRepositoryImpl(db.evaluateReportDao())
        val caseRecordId = "case-1"

        repo.saveFailure(caseRecordId, "IOException")
        assertEquals("IOException", repo.getFailureCodeForCase(caseRecordId))
        assertNull("a failure row must never read back as a real report", repo.getForCase(caseRecordId))

        repo.save(evaluateOutput(caseRecordId))

        assertNull("a successful retry must clear the failure marker", repo.getFailureCodeForCase(caseRecordId))
        assertTrue("a successful retry must read back as a real report", repo.getForCase(caseRecordId) != null)

        assertTrue("exactly one row must exist for this case after retry", db.evaluateReportDao().observeForCase(caseRecordId).first() != null)
        // save()'s entity default leaves syncState PENDING, so getPendingForSync's full row set is
        // a reliable proxy for "every row in the table right now" here — the definitive way to
        // catch a second, orphaned row that observeForCase's single-object query could hide (Room
        // would just pick one of two matches without erroring).
        assertEquals(
            "the failure and the real report must be the SAME physical row (same id), not two rows",
            1,
            db.evaluateReportDao().getPendingForSync().count { it.caseRecordId == caseRecordId },
        )
    }
}
