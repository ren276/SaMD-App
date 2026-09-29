package com.example.samdapp.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.samdapp.data.local.entity.CaseRecordEntity
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.RETRY_MIN_INTERVAL
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * The drain-eligibility gate (`SyncSql.PENDING_ELIGIBILITY_FRAGMENT`) executed by SQLite on a real
 * SQLCipher database, which is what `SyncDaoSqlContractTest` cannot do: it only reads the SQL as
 * text.
 *
 * A PENDING row is always eligible, even seconds after its last push, so send-to-doctor is not held
 * back by a cooldown meant for retries. A RETRYABLE row waits for [RETRY_MIN_INTERVAL].
 *
 * Uses its own database file, deleted around every test, and never `samd_app.db`.
 */
class SyncPendingEligibilityTest {

    private val dbName = "sync-eligibility-test.db"
    private val passphrase = "sync-eligibility-test-passphrase".toByteArray()
    private lateinit var db: AppDatabase

    init {
        System.loadLibrary("sqlcipher")
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(dbName)
        db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(dbName)
    }

    private val now: Instant = Instant.now()
    private val cutoff: Instant get() = now.minus(RETRY_MIN_INTERVAL)

    private fun caseRecord(id: String, state: SyncState, attemptedAgo: Duration?) = CaseRecordEntity(
        id = id, patientId = "pat-1", encounterId = "enc-1", status = CaseStatus.DRAFT,
        assignedDoctorId = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        syncState = state, serverVersion = 7, lastSyncAttemptAt = attemptedAgo?.let { now.minus(it) },
        localModifiedAt = Instant.EPOCH,
    )

    private suspend fun eligibleIds(): Set<String> =
        db.caseRecordDao().getPendingForSync(cutoff).map { it.id }.toSet()

    @Test
    fun aRecordSyncedOneMinuteAgoAndThenSentToTheDoctorIsEligibleImmediately() = runBlocking {
        val dao = db.caseRecordDao()
        dao.insert(caseRecord("case-1", SyncState.SYNCED, Duration.ofMinutes(1)))
        assertFalse("a SYNCED row is not collected", "case-1" in eligibleIds())

        dao.assignDoctor("case-1", "doc-1", CaseStatus.SENT_TO_DOCTOR, now)

        val edited = requireNotNull(dao.observeById("case-1").first())
        assertEquals(SyncState.PENDING, edited.syncState)
        assertTrue(
            "the edit must leave the last attempt inside the cooldown, or this test proves nothing",
            edited.lastSyncAttemptAt!!.isAfter(cutoff),
        )
        assertTrue("a PENDING edit bypasses the retry cooldown", "case-1" in eligibleIds())
    }

    @Test
    fun aRetryableRowAttemptedOneMinuteAgoIsNotCollected() = runBlocking {
        db.caseRecordDao().insert(caseRecord("case-1", SyncState.RETRYABLE, Duration.ofMinutes(1)))
        assertFalse("a RETRYABLE row inside the cooldown must wait", "case-1" in eligibleIds())
    }

    @Test
    fun theSameRetryableRowAttemptedSixMinutesAgoIsCollected() = runBlocking {
        db.caseRecordDao().insert(caseRecord("case-1", SyncState.RETRYABLE, Duration.ofMinutes(6)))
        assertTrue("a RETRYABLE row past the cooldown is eligible", "case-1" in eligibleIds())
    }

    @Test
    fun aRetryableRowNeverAttemptedIsCollected() = runBlocking {
        db.caseRecordDao().insert(caseRecord("case-1", SyncState.RETRYABLE, null))
        assertTrue("no attempt on record means no cooldown to wait out", "case-1" in eligibleIds())
    }
}
