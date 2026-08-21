package com.example.samdapp.data.repository

import com.example.samdapp.data.local.dao.KernelReportDao
import com.example.samdapp.data.local.entity.KernelReportEntity
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.domain.model.KernelReportOutput
import com.example.samdapp.domain.model.RiskCategory
import com.example.samdapp.domain.model.SyncState
import com.example.samdapp.domain.model.UrgencyLevel
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Mimics [KernelReportDao.upsert]'s real Room contract (`@Insert(onConflict = REPLACE)`, primary
 * key `id`): a row with a colliding `id` overwrites in place; any other `id` inserts a second row.
 * That distinction is exactly what [KernelReportRepositoryImplTest] needs proven — a fake that
 * upserted by `caseRecordId` instead would hide the MIGRATION_15_16 bug this test exists to catch.
 */
class FakeKernelReportDao : KernelReportDao {
    private val store = MutableStateFlow<List<KernelReportEntity>>(emptyList())

    override suspend fun upsert(report: KernelReportEntity) {
        val index = store.value.indexOfFirst { it.id == report.id }
        store.value = if (index >= 0) {
            store.value.toMutableList().also { it[index] = report }
        } else {
            store.value + report
        }
    }

    override fun observeForCase(caseRecordId: String): Flow<KernelReportEntity?> =
        store.map { rows -> rows.firstOrNull { it.caseRecordId == caseRecordId } }

    override suspend fun getIdForCase(caseRecordId: String): String? =
        store.value.firstOrNull { it.caseRecordId == caseRecordId }?.id

    override suspend fun getServerVersion(id: String): Int? =
        store.value.firstOrNull { it.id == id }?.serverVersion

    override suspend fun getPendingForSync(): List<KernelReportEntity> =
        store.value.filter { it.syncState == SyncState.PENDING }

    override suspend fun applySyncResult(
        id: String,
        syncState: SyncState,
        serverVersion: Int?,
        syncErrorCode: String?,
        attemptAt: Instant,
        sentLocalModifiedAt: Instant,
    ) {
        store.value = store.value.map {
            if (it.id == id && it.localModifiedAt == sentLocalModifiedAt) {
                it.copy(syncState = syncState, serverVersion = serverVersion ?: it.serverVersion, syncErrorCode = syncErrorCode, lastSyncAttemptAt = attemptAt)
            } else it
        }
    }

    override fun observeFailedSyncCount(): Flow<Int> =
        store.map { rows -> rows.count { it.syncState == SyncState.FAILED } }

    /** Read-back helper, standing in for a direct-DB assertion (no in-memory Room on plain JVM
     *  here) — the same role `MigrationTest15To16`'s cursor queries play on-device. */
    fun rowsForCase(caseRecordId: String): List<KernelReportEntity> =
        store.value.filter { it.caseRecordId == caseRecordId }
}

class KernelReportRepositoryImplTest {

    private lateinit var dao: FakeKernelReportDao
    private lateinit var repository: KernelReportRepositoryImpl

    @Before
    fun setUp() {
        dao = FakeKernelReportDao()
        repository = KernelReportRepositoryImpl(dao)
    }

    private fun report(
        id: String = UUID.randomUUID().toString(),
        caseRecordId: String = "case-1",
        predictedCondition: String = "Viral fever",
        inferenceSource: InferenceSource = InferenceSource.REAL_INFERENCE,
    ) = KernelReportOutput(
        id = id,
        caseRecordId = caseRecordId,
        predictedCondition = predictedCondition,
        confidenceScore = 0.8,
        differentials = emptyList(),
        reasoningSummary = "reasoning",
        evidenceFor = emptyList(),
        evidenceAgainst = emptyList(),
        modelVersion = "mock-kernel-v0.1",
        icdCode = null,
        deviceId = "dev",
        softwareVersion = "v1",
        dataQualityScore = null,
        uncertaintyScore = null,
        riskCategory = RiskCategory.MODERATE,
        urgencyLevel = UrgencyLevel.ROUTINE,
        inferenceStartedAt = Instant.ofEpochMilli(1000),
        inferenceEndedAt = Instant.ofEpochMilli(1000),
        requiredHumanVerification = false,
        inferenceSource = inferenceSource,
    )

    @Test
    fun secondSaveForTheSameCase_replacesTheExistingRowInsteadOfInsertingASecondOne() = runTest {
        // GenerateKernelReportUseCase mints a fresh id on every attempt — the two saves below use
        // different ids on purpose, the way the real use case would on a retry.
        repository.save(
            report(
                id = "attempt-1",
                caseRecordId = "case-1",
                predictedCondition = "Assessment unavailable",
                inferenceSource = InferenceSource.UNAVAILABLE,
            ),
        )
        repository.save(
            report(
                id = "attempt-2",
                caseRecordId = "case-1",
                predictedCondition = "Viral fever",
                inferenceSource = InferenceSource.REAL_INFERENCE,
            ),
        )

        // DAO read-back, not the save() return value: proves what actually persisted, per
        // CLAUDE.md's backend-conventions rule (a request-scoped success can still mask a bad
        // write, and here specifically it would mask an unwanted second row).
        val rows = dao.rowsForCase("case-1")
        assertEquals("expected the retry to REPLACE the row, not insert a second one", 1, rows.size)
        assertEquals("attempt-1", rows.single().id)
        assertEquals("Viral fever", rows.single().predictedCondition)
        assertEquals(InferenceSource.REAL_INFERENCE, rows.single().inferenceSource)
    }

    @Test
    fun secondSaveForTheSameCase_preservesServerVersionAcrossTheRetry() = runTest {
        repository.save(report(id = "attempt-1", caseRecordId = "case-1"))
        // Simulate a completed sync in between: the row got a serverVersion from the backend.
        dao.applySyncResult(
            id = dao.getIdForCase("case-1")!!,
            syncState = SyncState.SYNCED,
            serverVersion = 3,
            syncErrorCode = null,
            attemptAt = Instant.ofEpochMilli(5000),
            sentLocalModifiedAt = dao.rowsForCase("case-1").single().localModifiedAt,
        )

        repository.save(report(id = "attempt-2", caseRecordId = "case-1", predictedCondition = "Dengue"))

        val rows = dao.rowsForCase("case-1")
        assertEquals(1, rows.size)
        assertEquals(
            "serverVersion must survive the REPLACE-upsert on retry, not silently reset to null",
            3,
            rows.single().serverVersion,
        )
    }

    @Test
    fun firstSaveForANewCase_hasNoServerVersionToPreserve() = runTest {
        repository.save(report(id = "attempt-1", caseRecordId = "case-new"))

        assertNull(dao.rowsForCase("case-new").single().serverVersion)
    }
}
