package com.example.samdapp.data.local.audit

import com.example.samdapp.testutil.FakeAuditLogDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** REQ-AUD-01: an audit call inserts one row carrying the given action/ids/payload. */
class RoomAuditLoggerTest {

    @Test
    fun `log inserts a single entry with the given fields`() = runTest {
        val dao = FakeAuditLogDao()
        val logger = RoomAuditLogger(dao)

        logger.log(action = "encounter_started", patientId = "p1", caseRecordId = "c1", payload = "{}")

        val entry = dao.inserted.single()
        assertEquals("encounter_started", entry.action)
        assertEquals("p1", entry.patientId)
        assertEquals("c1", entry.caseRecordId)
        assertEquals("{}", entry.payload)
        assertTrue("id should be generated", entry.id.isNotBlank())
        assertTrue("userId placeholder should be set", entry.userId.isNotBlank())
    }

    @Test
    fun `log tolerates null patient and case ids`() = runTest {
        val dao = FakeAuditLogDao()
        RoomAuditLogger(dao).log(action = "transcription_completed", payload = "{}")

        val entry = dao.inserted.single()
        assertEquals(null, entry.patientId)
        assertEquals(null, entry.caseRecordId)
    }
}
