package com.example.samdapp.data.vitalssource.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** G-B30 (G1): a hub's association is found by the ID stored when it was created, never by name. */
class HubAssociationsTest {

    private class MemoryStore : AssociationIdStore {
        val ids = mutableMapOf<String, Int>()
        override fun get(hubId: String): Int? = ids[hubId]
        override fun put(hubId: String, associationId: Int) { ids[hubId] = associationId }
        override fun remove(hubId: String) { ids.remove(hubId) }
    }

    private val store = MemoryStore()
    private var listed = listOf<Association>()
    private val disassociated = mutableListOf<Int>()
    private var supported = true
    private val associations = HubAssociations(store, { listed }, { disassociated += it }, { supported })

    @Test
    fun `the ID stored at onAssociationCreated finds the association on the next Start`() {
        listed = listOf(Association(7, "AA:BB:CC:DD:EE:01", "SaMD-kernelhub2"))

        associations.onCreated("kernelhub2", 7)

        assertEquals(7, store.ids["kernelhub2"])
        assertEquals("AA:BB:CC:DD:EE:01", associations.addressFor("kernelhub2"))
    }

    @Test
    fun `no stored ID runs the association dialog`() {
        listed = listOf(Association(7, "AA:BB:CC:DD:EE:01", "SaMD-kernelhub2"))

        assertNull("an association that is merely listed is not this hub's", associations.addressFor("kernelhub2"))
    }

    @Test
    fun `a stored ID the system no longer lists runs the association dialog`() {
        store.ids["kernelhub2"] = 7
        listed = listOf(Association(9, "AA:BB:CC:DD:EE:09", "SaMD-kernelhub1"))

        assertNull(associations.addressFor("kernelhub2"))
    }

    @Test
    fun `an association named for the hub under another ID is not used`() {
        store.ids["kernelhub2"] = 7
        listed = listOf(
            Association(8, "AA:BB:CC:DD:EE:08", "SaMD-kernelhub2"),
            Association(7, "AA:BB:CC:DD:EE:07", "something else"),
        )
        assertEquals("AA:BB:CC:DD:EE:07", associations.addressFor("kernelhub2"))

        listed = listOf(Association(8, "AA:BB:CC:DD:EE:08", "SaMD-kernelhub2"))
        assertNull(associations.addressFor("kernelhub2"))
    }

    @Test
    fun `canAssociate is the platform's companion-device support`() {
        assertEquals(true, associations.canAssociate())
        supported = false
        assertEquals(false, associations.canAssociate())
    }

    @Test
    fun `forget disassociates the stored ID and deletes it, and a system failure does not escape`() {
        store.ids["kernelhub2"] = 7

        associations.forget("kernelhub2")

        assertEquals(listOf(7), disassociated)
        assertNull(store.ids["kernelhub2"])

        store.ids["kernelhub2"] = 8
        val failing = HubAssociations(store, { listed }, { throw IllegalArgumentException("unknown association") }, { true })
        val outcome = runCatching { failing.forget("kernelhub2") }
        assertEquals("the failure escaped: ${outcome.exceptionOrNull()}", true, outcome.isSuccess)
        assertNull(store.ids["kernelhub2"])
    }
}
