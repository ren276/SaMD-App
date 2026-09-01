package com.example.samdapp.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.samdapp.data.local.AppDatabase
import com.example.samdapp.domain.DataError
import com.example.samdapp.domain.model.Consultation
import com.example.samdapp.domain.model.FieldProvenance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * ASR track PR 3a (`scratchpad/pr3-voice-gate-design-memo.md` Part B, from the field-audit memo's
 * B.2). Proves the `VOICE_UNCONFIRMED` write-refusal against a real Room database.
 *
 * The assertion that matters is the **absence of the row**, read back through the DAO after the
 * call returns, not the returned `Result`. CLAUDE.md's rule is explicit that a test covering a
 * write that must not survive has to check the persisted state: a repository that returned a
 * failure while still inserting would pass a return-value-only test, and that is exactly the bug
 * class this control exists to prevent.
 *
 * [voiceConfirmed_persistsNormally] is the mandatory positive control. Without it, a repository
 * that refused every write would satisfy the refusal test.
 */
class ConsultationVoiceUnconfirmedRefusalTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ConsultationRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        repository = ConsultationRepositoryImpl(db.consultationDao(), db.attachmentDao())
    }

    @After
    fun tearDown() = db.close()

    private fun consultation(id: String, provenance: FieldProvenance?) = Consultation(
        id = id, patientId = "p1", encounterId = "e-$id", chiefComplaint = "Fever",
        onset = null, durationBucket = null, severityScore = null,
        aggravatingFactors = null, relievingFactors = null,
        impactOnDailyActivities = "Cannot go to work",
        impactOnDailyActivitiesProvenance = provenance,
        relevantHistory = null, transcription = null, attachments = emptyList(),
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )

    @Test
    fun voiceUnconfirmed_isRefusedAndNoRowIsPersisted() = runBlocking {
        val result = repository.saveConsultation(
            consultation("c-unconfirmed", FieldProvenance.VOICE_UNCONFIRMED),
        )

        // The real assertion: nothing landed in the database.
        assertNull(
            "an unconfirmed voice value must not reach the consultations table",
            db.consultationDao().observeForEncounter("e-c-unconfirmed").first(),
        )

        // Secondary: the caller is told the write was refused, and told so distinguishably.
        assertTrue("the refused write must report failure", result.isFailure)
        assertTrue(
            "a refusal must surface as DataError.Refused, not as a generic storage error",
            result.exceptionOrNull() is DataError.Refused,
        )
    }

    @Test
    fun voiceConfirmed_persistsNormally() = runBlocking {
        val result = repository.saveConsultation(
            consultation("c-confirmed", FieldProvenance.VOICE_CONFIRMED),
        )

        assertTrue("a confirmed voice value must save", result.isSuccess)
        val persisted = db.consultationDao().observeForEncounter("e-c-confirmed").first()
        assertEquals(
            "the confirmed value must be readable from the stored row",
            FieldProvenance.VOICE_CONFIRMED,
            persisted?.impactOnDailyActivitiesProvenance,
        )
        assertEquals("Cannot go to work", persisted?.impactOnDailyActivities)
    }

    @Test
    fun typedAndNullProvenance_areUnaffectedByTheRefusal() = runBlocking {
        assertTrue(repository.saveConsultation(consultation("c-typed", FieldProvenance.TYPED)).isSuccess)
        assertTrue(repository.saveConsultation(consultation("c-none", null)).isSuccess)

        assertEquals(
            FieldProvenance.TYPED,
            db.consultationDao().observeForEncounter("e-c-typed").first()?.impactOnDailyActivitiesProvenance,
        )
        assertNull(
            db.consultationDao().observeForEncounter("e-c-none").first()?.impactOnDailyActivitiesProvenance,
        )
    }
}
