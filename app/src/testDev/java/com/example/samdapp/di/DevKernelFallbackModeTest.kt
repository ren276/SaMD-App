package com.example.samdapp.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The flag-to-binding selector behind `samd.dev.kernelFallback` in local.properties. Absent must
 * keep today's dev behaviour (the mock scenario), and `none` must bind the always-null fallback
 * staging and prod use, so the honest UNAVAILABLE card and Retry can be exercised live on a dev
 * build. A typo must not silently fall back to the mock: a tester who believes the mock is off
 * and sees a mock result is the exact confusion this switch exists to remove.
 */
class DevKernelFallbackModeTest {

    @Test
    fun `absent or blank keeps the mock`() {
        assertEquals(DevKernelFallbackMode.MOCK, devKernelFallbackMode(""))
        assertEquals(DevKernelFallbackMode.MOCK, devKernelFallbackMode("   "))
    }

    @Test
    fun `mock selects the mock`() {
        assertEquals(DevKernelFallbackMode.MOCK, devKernelFallbackMode("mock"))
    }

    @Test
    fun `none selects the always-null fallback, case and whitespace insensitive`() {
        assertEquals(DevKernelFallbackMode.NONE, devKernelFallbackMode("none"))
        assertEquals(DevKernelFallbackMode.NONE, devKernelFallbackMode(" NONE "))
    }

    @Test
    fun `an unknown value fails loudly instead of silently keeping the mock`() {
        val error = assertThrows(IllegalArgumentException::class.java) { devKernelFallbackMode("off") }
        assertEquals(true, error.message!!.contains("samd.dev.kernelFallback"))
    }
}
