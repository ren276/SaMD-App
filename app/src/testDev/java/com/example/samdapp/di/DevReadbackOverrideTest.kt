package com.example.samdapp.di

import com.example.samdapp.domain.auth.SessionFacility
import com.example.samdapp.domain.auth.SessionFacilitySource
import com.example.samdapp.domain.slm.OwningFacility
import com.example.samdapp.domain.slm.facilityScopeAllows
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ruling R-DEV (on-device SLM memo 16): in dev the owner of the local data is the signed-in
 * session's facility, so the seam's facility check (step 0, Y-21') lets a signed-in worker's
 * readback through and still refuses when nobody is signed in. Which engine dev resolves is pinned
 * by `ReadbackFlavorGraphTest` and `SlmEngineIsUnreachableFromPresentationTest`.
 */
class DevReadbackOverrideTest {

    private class Session(private val facility: SessionFacility?) : SessionFacilitySource {
        override suspend fun currentFacility(): SessionFacility? = facility
    }

    @Test
    fun theDevOwnerIsTheSignedInSessionsFacilitySoTheScopeCheckPasses() = runTest {
        val session = SessionFacility("fac-7", "PHC Rampur")
        val owner = LocalSlmModule.provideDevLocalDataOwnerFacility(Session(session)).owningFacility()
        assertEquals(OwningFacility.Known("fac-7"), owner)
        assertTrue("a signed-in dev readback was refused at the facility check", facilityScopeAllows(session, owner))
    }

    @Test
    fun signedOutTheDevOwnerIsUnknownAndTheScopeCheckRefuses() = runTest {
        val owner = LocalSlmModule.provideDevLocalDataOwnerFacility(Session(null)).owningFacility()
        assertEquals(OwningFacility.Unknown, owner)
        assertFalse(facilityScopeAllows(null, owner))
    }
}
