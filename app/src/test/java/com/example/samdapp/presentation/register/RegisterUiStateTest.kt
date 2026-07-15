package com.example.samdapp.presentation.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** REQ-REG-01, REQ-REG-02: contact-required + digit-length validation gating submission. */
class RegisterUiStateTest {

    private fun state(vararg fields: Pair<RegisterField, String>) =
        RegisterUiState(fields = mapOf(*fields))

    @Test
    fun `cannot submit without a full name`() {
        val s = state(RegisterField.MOBILE_NUMBER to "9998887776")
        assertFalse(s.canSubmit)
    }

    @Test
    fun `cannot submit without any contact method`() {
        val s = state(RegisterField.FULL_NAME to "Asha")
        assertFalse(s.canSubmit)
    }

    @Test
    fun `can submit with name and valid mobile`() {
        val s = state(RegisterField.FULL_NAME to "Asha", RegisterField.MOBILE_NUMBER to "9998887776")
        assertTrue(s.canSubmit)
    }

    @Test
    fun `can submit with name and an address instead of phone`() {
        val s = state(RegisterField.FULL_NAME to "Asha", RegisterField.VILLAGE to "Bhainsa")
        assertTrue(s.canSubmit)
    }

    @Test
    fun `mobile shorter than ten digits is a field error and blocks submit`() {
        val s = state(RegisterField.FULL_NAME to "Asha", RegisterField.MOBILE_NUMBER to "123")
        assertEquals("Must be 10 digits", s.fieldError(RegisterField.MOBILE_NUMBER))
        assertFalse(s.canSubmit)
    }

    @Test
    fun `mobile with non-digits is a field error`() {
        val s = state(RegisterField.FULL_NAME to "Asha", RegisterField.MOBILE_NUMBER to "99988877ab")
        assertEquals("Must be 10 digits", s.fieldError(RegisterField.MOBILE_NUMBER))
    }

    @Test
    fun `valid mobile has no field error`() {
        val s = state(RegisterField.MOBILE_NUMBER to "9998887776")
        assertNull(s.fieldError(RegisterField.MOBILE_NUMBER))
    }

    @Test
    fun `pincode must be six digits`() {
        val s = state(RegisterField.PINCODE to "1234")
        assertEquals("Must be 6 digits", s.fieldError(RegisterField.PINCODE))
    }
}
