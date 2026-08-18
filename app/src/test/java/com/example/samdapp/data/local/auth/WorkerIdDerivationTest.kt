package com.example.samdapp.data.local.auth

import com.example.samdapp.domain.auth.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Locks the `sha256(name.trim().lowercase() + "|" + role.name)[:16]` formula that
 * [MockAuthSession] and [BackendAuthSession] both depend on for audit continuity across the
 * mock-to-real cutover (api-contract.md §2.1): the backend provisions `user_accounts.worker_id`
 * to this exact value, so a change here silently fractures every existing worker's audit trail.
 */
class WorkerIdDerivationTest {

    @Test
    fun `matches the documented sha256 formula for a known input`() {
        // sha256("gayatri|ASHA_WORKER"), first 16 hex chars, computed independently, not derived
        // from the function under test.
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest("gayatri|ASHA_WORKER".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)

        assertEquals(expected, stableUserId("Gayatri", UserRole.ASHA_WORKER))
    }

    @Test
    fun `is deterministic across repeated calls`() {
        assertEquals(stableUserId("Gayatri", UserRole.NURSE), stableUserId("Gayatri", UserRole.NURSE))
    }

    @Test
    fun `trims whitespace and lowercases the name before hashing`() {
        assertEquals(stableUserId("gayatri", UserRole.COMPOUNDER), stableUserId("  Gayatri  ", UserRole.COMPOUNDER))
    }

    @Test
    fun `same name with a different role yields a different id`() {
        assertNotEquals(stableUserId("Gayatri", UserRole.ASHA_WORKER), stableUserId("Gayatri", UserRole.NURSE))
    }

    @Test
    fun `is always 16 lowercase hex characters, matching the backend's worker_id pattern`() {
        val id = stableUserId("Dr. Rekha", UserRole.DOCTOR)
        assertEquals(16, id.length)
        assertEquals(id, Regex("^[0-9a-f]{16}$").find(id)?.value)
    }
}
