package com.example.samdapp.data.local.audit

import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.domain.auth.UserSession
import com.example.samdapp.testutil.FakeAuditLogDao
import com.example.samdapp.testutil.FakeAuthSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** REQ-AUD-01 / REQ-SEC-04: an audit call inserts one row carrying the given action/ids/payload,
 * with userId sourced from the real signed-in session, not a hardcoded placeholder. */
class RoomAuditLoggerTest {

    @Test
    fun `log inserts a single entry with the given fields`() = runTest {
        val dao = FakeAuditLogDao()
        val logger = RoomAuditLogger(dao, FakeAuthSession())

        logger.log(action = AuditAction.ENCOUNTER_STARTED, patientId = "p1", caseRecordId = "c1", payload = "{}")

        val entry = dao.inserted.single()
        assertEquals(AuditAction.ENCOUNTER_STARTED.value, entry.action)
        assertEquals("p1", entry.patientId)
        assertEquals("c1", entry.caseRecordId)
        assertEquals("{}", entry.payload)
        assertTrue("id should be generated", entry.id.isNotBlank())
        assertTrue("userId placeholder should be set", entry.userId.isNotBlank())
    }

    @Test
    fun `log tolerates null patient and case ids`() = runTest {
        val dao = FakeAuditLogDao()
        RoomAuditLogger(dao, FakeAuthSession()).log(action = AuditAction.TRANSCRIPTION_COMPLETED, payload = "{}")

        val entry = dao.inserted.single()
        assertEquals(null, entry.patientId)
        assertEquals(null, entry.caseRecordId)
    }

    @Test
    fun `log uses the signed-in session's userId, not the placeholder`() = runTest {
        val dao = FakeAuditLogDao()
        val session = FakeAuthSession(UserSession(userId = "real-user-123", name = "Asha Devi", role = UserRole.ASHA_WORKER))

        RoomAuditLogger(dao, session).log(action = AuditAction.ENCOUNTER_STARTED, payload = "{}")

        assertEquals("real-user-123", dao.inserted.single().userId)
    }

    @Test
    fun `log falls back to the placeholder when no session is active`() = runTest {
        val dao = FakeAuditLogDao()

        RoomAuditLogger(dao, FakeAuthSession(initialSession = null)).log(action = AuditAction.ENCOUNTER_STARTED, payload = "{}")

        assertEquals("phc_field_worker", dao.inserted.single().userId)
    }
}
