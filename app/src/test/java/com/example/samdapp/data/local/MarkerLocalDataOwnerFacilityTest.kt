package com.example.samdapp.data.local

import com.example.samdapp.data.local.dao.DeviceDataOwnerDao
import com.example.samdapp.data.local.entity.DeviceDataOwnerEntity
import com.example.samdapp.domain.auth.SessionFacility
import com.example.samdapp.domain.slm.OwningFacility
import com.example.samdapp.domain.slm.facilityScopeAllows
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The readback owner read from the marker (facility-scope memo 3.6, FS-7), through the seam's own
 *  facility check. */
class MarkerLocalDataOwnerFacilityTest {

    private class OwnerDao(private val owner: DeviceDataOwnerEntity?) : DeviceDataOwnerDao {
        override suspend fun insertIfAbsent(owner: DeviceDataOwnerEntity): Long = error("unused")
        override suspend fun read(): DeviceDataOwnerEntity? = owner
    }

    private fun marker(id: String, name: String) =
        DeviceDataOwnerEntity(facilityId = id, facilityName = name, claimedAt = Instant.EPOCH, claimedByWorkerId = "w-1", legacy = false)

    private suspend fun ownerOf(entity: DeviceDataOwnerEntity?) = MarkerLocalDataOwnerFacility(OwnerDao(entity)).owningFacility()

    private val session = SessionFacility("fac-a", "PHC Alpha")

    @Test
    fun `no marker is an unknown owner and the readback is refused`() = runTest {
        val owner = ownerOf(null)
        assertEquals(OwningFacility.Unknown, owner)
        assertFalse(facilityScopeAllows(session, owner))
    }

    @Test
    fun `a marker equal to the session's facility allows the readback`() = runTest {
        val owner = ownerOf(marker("fac-a", "PHC Alpha"))
        assertEquals(OwningFacility.Known("fac-a"), owner)
        assertTrue(facilityScopeAllows(session, owner))
    }

    @Test
    fun `a marker of another facility refuses the readback, even under the same name`() = runTest {
        assertFalse(facilityScopeAllows(session, ownerOf(marker("fac-b", "PHC Beta"))))
        assertFalse("ids compare, never names", facilityScopeAllows(session, ownerOf(marker("fac-b", "PHC Alpha"))))
    }

    @Test
    fun `a marker with a blank id is an unknown owner`() = runTest {
        assertEquals(OwningFacility.Unknown, ownerOf(marker(" ", "PHC Alpha")))
    }
}
