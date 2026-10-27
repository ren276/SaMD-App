package com.example.samdapp.di

import com.example.samdapp.domain.slm.LocalDataOwnerFacility
import com.example.samdapp.domain.slm.OwningFacility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ruling R-DEV (on-device SLM memo 16) gave dev an owner override: the signed-in session's
 * facility. Facility-scope FS-7 superseded it: dev's owner is the `device_data_owner` marker, as in
 * every flavor (`ReadbackFlavorGraphTest`), so the dev holder provides the engine override and
 * nothing that answers [LocalDataOwnerFacility]. Which engine dev resolves is pinned by
 * `ReadbackFlavorGraphTest` and `SlmEngineIsUnreachableFromPresentationTest`.
 */
class DevReadbackOverrideTest {

    private fun ownerProviders(holder: Class<*>) =
        holder.declaredMethods.filter { LocalDataOwnerFacility::class.java.isAssignableFrom(it.returnType) }.map { it.name }

    @Test
    fun theDevHolderNoLongerProvidesAnOwner() {
        assertEquals(emptyList<String>(), ownerProviders(LocalSlmModule::class.java))
    }

    /** Positive control: a holder that still provides the session-facility owner is caught. */
    @Test
    fun aHolderThatProvidesAnOwnerIsCaught() {
        assertTrue(ownerProviders(WithOwner::class.java).contains("provideDevLocalDataOwnerFacility"))
    }

    private object WithOwner {
        @Suppress("unused")
        fun provideDevLocalDataOwnerFacility(): LocalDataOwnerFacility = object : LocalDataOwnerFacility {
            override suspend fun owningFacility(): OwningFacility = OwningFacility.Unknown
        }
    }
}
