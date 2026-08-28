package com.example.samdapp.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.samdapp.data.local.entity.CaseRecordEntity
import com.example.samdapp.data.repository.CaseRecordRepositoryImpl
import com.example.samdapp.domain.model.CaseStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The async submission queue's day-ordinal receipt (`CaseRecordDao.getDayOrdinal`, and
 * `CaseRecordRepositoryImpl.getDayOrdinal` end to end) — a real Room DB, not the JVM fake,
 * because this is asserting actual SQL millis-comparison behavior across a day boundary, not
 * orchestration logic. `case_records` is never deleted (see the DAO KDoc), so ordinals are
 * asserted by re-reading through the DAO/repository, never from a mutation's return value.
 */
class CaseRecordDayOrdinalDaoTest {

    private lateinit var db: AppDatabase
    private val zone = ZoneId.systemDefault()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = db.close()

    private fun dayStartMillis(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun caseRecord(id: String, createdAtMillis: Long) = CaseRecordEntity(
        id = id, patientId = "p1", encounterId = "enc-$id", status = CaseStatus.SENT_TO_DOCTOR,
        assignedDoctorId = null,
        createdAt = Instant.ofEpochMilli(createdAtMillis), updatedAt = Instant.ofEpochMilli(createdAtMillis),
        localModifiedAt = Instant.ofEpochMilli(createdAtMillis),
    )

    @Test
    fun secondCaseCreatedTheSameDayIsOrdinalTwo() = runBlocking {
        val dao = db.caseRecordDao()
        val today = LocalDate.of(2026, 8, 28)
        val startMillis = dayStartMillis(today)
        val endMillis = dayStartMillis(today.plusDays(1))
        dao.insert(caseRecord("case-a", startMillis + 1_000))
        dao.insert(caseRecord("case-b", startMillis + 2_000))

        val ordinal = dao.getDayOrdinal(startMillis, endMillis, startMillis + 2_000, "case-b")

        assertEquals(2, ordinal)
    }

    @Test
    fun aCaseCreatedAt2359KeepsItsNumber_and0001TheNextDayIsNumberOneOfTheNewDay() = runBlocking {
        val dao = db.caseRecordDao()
        val day1 = LocalDate.of(2026, 8, 28)
        val day2 = day1.plusDays(1)
        val day1Start = dayStartMillis(day1)
        val day1End = dayStartMillis(day2)
        val day2Start = day1End
        val day2End = dayStartMillis(day2.plusDays(1))

        val lateNightMillis = day1End - 60_000 // 23:59 on day1
        dao.insert(caseRecord("late-case", lateNightMillis))
        val earlyMorningMillis = day2Start + 60_000 // 00:01 on day2
        dao.insert(caseRecord("early-case", earlyMorningMillis))

        val lateOrdinal = dao.getDayOrdinal(day1Start, day1End, lateNightMillis, "late-case")
        val earlyOrdinal = dao.getDayOrdinal(day2Start, day2End, earlyMorningMillis, "early-case")

        assertEquals(1, lateOrdinal)
        assertEquals(1, earlyOrdinal)
    }

    @Test
    fun ordinalIsStableAcrossARepeatedReadAndAfterAThirdCaseIsAddedLater() = runBlocking {
        val dao = db.caseRecordDao()
        val today = LocalDate.of(2026, 8, 28)
        val startMillis = dayStartMillis(today)
        val endMillis = dayStartMillis(today.plusDays(1))
        dao.insert(caseRecord("case-a", startMillis + 1_000))
        dao.insert(caseRecord("case-b", startMillis + 2_000))

        val first = dao.getDayOrdinal(startMillis, endMillis, startMillis + 2_000, "case-b")

        dao.insert(caseRecord("case-c", startMillis + 3_000))
        val second = dao.getDayOrdinal(startMillis, endMillis, startMillis + 2_000, "case-b")

        assertEquals(2, first)
        assertEquals(first, second)
    }

    @Test
    fun repositoryComputesTheWindowFromTheCasesOwnCreatedAt_notFromNow() = runBlocking {
        // Exercises CaseRecordRepositoryImpl.getDayOrdinal end to end: the case is created far in
        // the past, and its ordinal must still resolve against ITS OWN day, never against
        // "today" as observed by the test clock (LocalDate.now() would return the wrong window).
        val dao = db.caseRecordDao()
        val pastDay = LocalDate.of(2020, 1, 1)
        val pastMillis = dayStartMillis(pastDay) + 5_000
        dao.insert(caseRecord("old-case", pastMillis))
        val repository = CaseRecordRepositoryImpl(dao)

        val ordinal = repository.getDayOrdinal("old-case")

        assertEquals(1, ordinal)
    }

    @Test
    fun missingCaseRecordReturnsNullOrdinal() = runBlocking {
        val dao = db.caseRecordDao()
        val repository = CaseRecordRepositoryImpl(dao)

        val ordinal = repository.getDayOrdinal("no-such-case")

        assertEquals(null, ordinal)
    }
}
