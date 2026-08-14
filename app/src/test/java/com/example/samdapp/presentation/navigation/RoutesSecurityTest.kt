package com.example.samdapp.presentation.navigation

import com.example.samdapp.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 1, privacy hardening: FLAG_SECURE route classification.
 *
 * [BuildConfig.SCREEN_SECURITY_ENABLED] is flavor-gated (off for dev, on for staging/prod — see
 * app/build.gradle.kts), so this asserts against whichever value the running flavor supplies
 * rather than assuming it's always on. Run via testDevDebugUnitTest, testStagingDebugUnitTest,
 * testProdDebugUnitTest to cover both states.
 */
class RoutesSecurityTest {

    @Test
    fun `patient-data routes require screen security when the flavor enables it`() {
        val patientDataRoutes = listOf(
            PatientSummary("p1"),
            Register(),
            Compounder("p1"),
            ReportRoute("case1"),
            PatientAuditRoute("p1"),
            DoctorListRoute,
        )
        if (BuildConfig.SCREEN_SECURITY_ENABLED) {
            patientDataRoutes.forEach { assertTrue(requiresScreenSecurity(it)) }
        } else {
            patientDataRoutes.forEach { assertFalse(requiresScreenSecurity(it)) }
        }
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
