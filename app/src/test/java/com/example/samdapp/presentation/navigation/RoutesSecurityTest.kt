package com.example.samdapp.presentation.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Item 1, privacy hardening: FLAG_SECURE route classification. */
class RoutesSecurityTest {

    @Test
    fun `patient-data routes require screen security`() {
        assertTrue(requiresScreenSecurity(PatientSummary("p1")))
        assertTrue(requiresScreenSecurity(Register()))
        assertTrue(requiresScreenSecurity(Compounder("p1")))
        assertTrue(requiresScreenSecurity(ReportRoute("case1")))
        assertTrue(requiresScreenSecurity(PatientAuditRoute("p1")))
        assertTrue(requiresScreenSecurity(DoctorListRoute))
    }

    @Test
    fun `low-sensitivity or dataless routes do not require screen security`() {
        assertFalse(requiresScreenSecurity(Home))
        assertFalse(requiresScreenSecurity(Patients))
        assertFalse(requiresScreenSecurity(Profile))
        assertFalse(requiresScreenSecurity(AbhaEntry))
    }

    @Test
    fun `null route does not require screen security`() {
        assertFalse(requiresScreenSecurity(null))
    }
}
