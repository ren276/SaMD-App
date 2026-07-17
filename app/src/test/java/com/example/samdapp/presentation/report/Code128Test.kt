package com.example.samdapp.presentation.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Code128Test {

    @Test
    fun `empty input yields no bars`() {
        assertTrue(Code128.encodeB("").isEmpty())
    }

    @Test
    fun `encoding always begins with a bar and ends with a bar`() {
        val bars = Code128.encodeB("UID123456789")
        assertTrue(bars.isNotEmpty())
        assertTrue(bars.first().isBar)
        // Stop pattern's final module is a bar, so the sequence ends on a bar.
        assertTrue(bars.last().isBar)
    }

    @Test
    fun `module count matches start + data + checksum + stop symbol widths`() {
        // Each Code 128 data symbol is 11 modules wide; the stop symbol is 13. For a 1-char payload
        // that's Start(11) + data(11) + checksum(11) + stop(13) = 46 modules.
        val bars = Code128.encodeB("A")
        assertEquals(46, Code128.totalModules(bars))
    }

    @Test
    fun `each data symbol is 11 modules wide (Code 128 invariant)`() {
        // Payload of 3 chars → 3 + 3(start/data.../checksum minus stop) symbols of 11 + stop 13.
        // Generalized: (symbols-1)*11 + 13, symbols = 3 data + start + checksum = but simplest check:
        // total minus stop(13) must be divisible by 11.
        val bars = Code128.encodeB("ABC")
        assertEquals(0, (Code128.totalModules(bars) - 13) % 11)
    }

    @Test
    fun `every bar has a module width between 1 and 4`() {
        Code128.encodeB("Rx-CR-001").forEach { assertTrue(it.modules in 1..4) }
    }

    @Test
    fun `a character outside subset B returns empty rather than a wrong barcode`() {
        // Tab (ASCII 9) is below the printable range subset B supports.
        assertTrue(Code128.encodeB("bad\tchar").isEmpty())
    }
}
