package com.example.samdapp.domain.auth

import com.example.samdapp.domain.auth.FacilitySignInDecision.Block
import com.example.samdapp.domain.auth.FacilitySignInDecision.Claim
import com.example.samdapp.domain.auth.FacilitySignInDecision.Continue
import com.example.samdapp.domain.auth.FacilitySignInDecision.LegacyClaim
import com.example.samdapp.domain.auth.FacilitySignInDecision.WipeThenClaim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Every row of facility-scope memo 3.2 plus FS-4(a), each against every outbox shape: drained, and
 * each of the five ways a row can still be owed to the server on its own.
 */
class FacilitySignInDecisionTest {

    private val facilityA = SessionFacility("fac-a", "PHC Alpha")
    private val facilityB = SessionFacility("fac-b", "PHC Beta")

    private val drained = SignInOutbox()
    private val notSent = mapOf(
        "pending clinical" to SignInOutbox(pendingClinical = 1),
        "held clinical" to SignInOutbox(heldClinical = 1),
        "pending audit" to SignInOutbox(pendingAudit = 1),
        "failed" to SignInOutbox(failed = 1),
        "conflict" to SignInOutbox(conflict = 1),
    )
    private val everyOutbox = notSent + ("drained" to drained)

    private fun decide(login: SessionFacility, owner: SessionFacility?, holdsRows: Boolean, outbox: SignInOutbox) =
        decideFacilitySignIn(login, owner, holdsRows, outbox)

    @Test
    fun `no owner and an empty database claims, whatever the outbox says`() {
        everyOutbox.forEach { (name, outbox) ->
            assertEquals(name, Claim, decide(facilityB, owner = null, holdsRows = false, outbox))
        }
    }

    @Test
    fun `the owning facility continues, whatever the outbox says`() {
        everyOutbox.forEach { (name, outbox) ->
            assertEquals(name, Continue, decide(facilityA, owner = facilityA, holdsRows = true, outbox))
        }
    }

    @Test
    fun `another facility over a drained outbox wipes then claims`() {
        assertEquals(WipeThenClaim, decide(facilityB, owner = facilityA, holdsRows = true, drained))
    }

    @Test
    fun `another facility over anything not sent is blocked, naming the owner`() {
        notSent.forEach { (name, outbox) ->
            assertEquals(name, Block("PHC Alpha"), decide(facilityB, owner = facilityA, holdsRows = true, outbox))
        }
    }

    @Test
    fun `no owner over existing data is the legacy claim, whatever the outbox says`() {
        everyOutbox.forEach { (name, outbox) ->
            assertEquals(name, LegacyClaim, decide(facilityB, owner = null, holdsRows = true, outbox))
        }
    }

    @Test
    fun `facility ids compare exactly and never by name`() {
        val sameNameOtherId = SessionFacility("fac-b", "PHC Alpha")
        assertEquals(Block("PHC Alpha"), decide(sameNameOtherId, owner = facilityA, holdsRows = true, SignInOutbox(pendingClinical = 1)))
        assertEquals(WipeThenClaim, decide(sameNameOtherId, owner = facilityA, holdsRows = true, drained))

        val sameIdOtherName = SessionFacility("fac-a", "PHC Alpha (renamed)")
        assertEquals(Continue, decide(sameIdOtherName, owner = facilityA, holdsRows = true, SignInOutbox(pendingClinical = 1)))

        val caseOnly = SessionFacility("FAC-A", "PHC Alpha")
        assertEquals(Block("PHC Alpha"), decide(caseOnly, owner = facilityA, holdsRows = true, SignInOutbox(pendingClinical = 1)))
    }

    @Test
    fun `a login without a facility id is refused, not decided`() {
        listOf("", "  ").forEach { blank ->
            assertThrows(IllegalArgumentException::class.java) {
                decide(SessionFacility(blank, "PHC Alpha"), owner = null, holdsRows = false, drained)
            }
        }
    }
}
