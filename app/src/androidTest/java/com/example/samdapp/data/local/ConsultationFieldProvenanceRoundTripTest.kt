package com.example.samdapp.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.samdapp.data.local.entity.ConsultationEntity
import com.example.samdapp.domain.model.FieldProvenance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * ASR track PR 1 (`scratchpad/asr-field-audit-memo.md` Part B.2). Proves
 * `impactOnDailyActivitiesProvenance` round-trips through a real Room database and its
 * [com.example.samdapp.data.local.Converters] `FieldProvenance` TypeConverter — asserting the
 * value read back from a fresh query, not the object handed to `insert()`, per CLAUDE.md's
 * persisted-row convention (a converter bug or a column-name typo would still let the in-memory
 * object look correct while the stored row does not).
 */
class ConsultationFieldProvenanceRoundTripTest {

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

    private fun entity(id: String, provenance: FieldProvenance?) = ConsultationEntity(
        id = id, patientId = "p1", encounterId = "e-$id", chiefComplaint = "Fever",
        onset = null, durationBucket = null, severityScore = null,
        aggravatingFactors = null, relievingFactors = null,
        impactOnDailyActivities = "Cannot go to work",
        impactOnDailyActivitiesProvenance = provenance,
        relevantHistory = null, transcription = null,
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, localModifiedAt = Instant.EPOCH,
    )

    @Test
    fun typedProvenance_roundTripsThroughARealPersistedRow() = runBlocking {
        val dao = db.consultationDao()
        dao.insert(entity("c-typed", FieldProvenance.TYPED))

        val persisted = dao.observeForEncounter("e-c-typed").first()

        assertEquals(
            "TYPED must survive a real write-then-read through the FieldProvenance TypeConverter",
            FieldProvenance.TYPED,
            persisted?.impactOnDailyActivitiesProvenance,
        )
    }

    @Test
    fun nullProvenance_roundTripsAsNull_notAsTheStringLiteralNull() = runBlocking {
        val dao = db.consultationDao()
        dao.insert(entity("c-null", null))

        val persisted = dao.observeForEncounter("e-c-null").first()

        assertNull(
            "a legacy/unset row must read back null, not a stray value",
            persisted?.impactOnDailyActivitiesProvenance,
        )
    }
}
