package com.example.samdapp.data.vitalssource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** R2. The emulator build is audited in the hub's own label format (SaMDPi `hubctx.py`), the same
 *  one DIS Software Revision and the hub journal show, so an audit row can be matched to either. */
class EmulatorBuildLabelTest {

    private fun label(dirty: Boolean?, merged: Boolean?, sha: String? = "bba2c6b1b") =
        EmulatorBuildDto(sha = sha, dirty = dirty, merged = merged).label()

    @Test
    fun `clean and merged is the bare sha`() = assertEquals("bba2c6b1b", label(dirty = false, merged = true))

    @Test
    fun `dirty and merged adds -dirty`() = assertEquals("bba2c6b1b-dirty", label(dirty = true, merged = true))

    @Test
    fun `clean and unmerged adds -unmerged`() = assertEquals("bba2c6b1b-unmerged", label(dirty = false, merged = false))

    @Test
    fun `dirty and unmerged adds -dirty then -unmerged`() =
        assertEquals("bba2c6b1b-dirty-unmerged", label(dirty = true, merged = false))

    @Test
    fun `a flag the hub did not send is not read as clean or merged`() {
        assertEquals("bba2c6b1b-dirty", label(dirty = null, merged = true))
        assertEquals("bba2c6b1b-unmerged", label(dirty = false, merged = null))
    }

    @Test
    fun `no sha means no label`() {
        assertNull(label(dirty = false, merged = true, sha = null))
        assertNull(label(dirty = false, merged = true, sha = " "))
    }
}
