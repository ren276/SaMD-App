package com.example.samdapp.presentation.navigation

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.savedstate.SavedState
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * V4. The regression guard for the ids-only route reshape.
 *
 * The saved back stack lands in the Activity's `onSaveInstanceState` Bundle, which is process
 * memory held by the system server: not SQLCipher, not `samd_document_key`, none of the
 * protections the rest of this app's PHI sits behind. What a route declares as an argument is
 * what ends up there.
 *
 * READ THIS BEFORE "FIXING" A FAILURE HERE. The test asserts both directions on purpose:
 *
 *  - values the reshape REMOVED must be absent, and
 *  - three values deliberately RETAINED this phase must still be present.
 *
 * The retained assertions are not an oversight and must not be made to pass by dropping routes
 * from the fixture. They are the evidence that phase 2's restore-time pop policies are actually
 * necessary: the day someone removes those values from the routes, these assertions fail and the
 * pops can be reconsidered on purpose rather than by accident.
 */
@RunWith(AndroidJUnit4::class)
class NavBackStackPhiFreeTest {

    private val saverScope = SaverScope { true }

    private companion object {
        const val COMPLAINT_PROBE = "ZZPROBE cough three days"
        const val MASKED_MOBILE_PROBE = "XXXXXX9999"
        const val ABHA_PROBE = "91-1234-5678-9012"

        // Never carried by any route any more. The emergency screen re-derives this text from the
        // persisted vitals row, and the audio uri is read from the consultation's attachment row.
        const val EMERGENCY_REASON_PROBE = "SpO2 85% is below the 90% floor"
        const val AUDIO_PATH_PROBE = "/data/user/0/com.example.samdapp.dev/cache/audio/rec-1.m4a"
    }

    /** Every route that carries an argument, so nothing escapes the assertions by not being here. */
    private fun everyArgumentCarryingRoute() = NavBackStack<NavKey>(
        Home,
        PatientSummary("pat-1"),
        MedicalBackground("pat-1"),
        PatientAuditRoute("pat-1"),
        DocumentViewerRoute("doc-1"),
        AbhaOtpRoute(ABHA_PROBE),
        AbhaCreateOtpRoute("session-1", MASKED_MOBILE_PROBE),
        AbhaProfileRoute(ABHA_PROBE),
        Register(ABHA_PROBE),
        ConsentRoute("pat-1", followUpOfEncounterId = "enc-0"),
        Compounder("pat-1", "enc-0", "enc-1", "case-1"),
        EmergencyOverrideRoute("pat-1", "enc-1"),
        ConsultationRoute("pat-1", "enc-1", "case-1", COMPLAINT_PROBE),
        SendingRoute("case-1", "consult-1", "enc-1"),
        KernelAssessmentRoute("case-1", "consult-1"),
        TranscriptionRoute("consult-1", "case-1"),
        AcknowledgementRoute("case-1"),
        ReportRoute("case-1"),
        ConsultationChainRoute("pat-1", "enc-root"),
        DoctorAssignmentConfirmRoute("case-1"),
    )

    private fun Saver<NavBackStack<NavKey>, SavedState>.saveStack(
        stack: NavBackStack<NavKey>,
    ): SavedState? = with(saverScope) { save(stack) }

    private fun persistedPayload(): String {
        val saved = navBackStackSaver(ownerUserId = "worker-a").saveStack(everyArgumentCarryingRoute())
        assertNotNull("The fixture must actually persist, or every assertion below is vacuous", saved)
        return saved!!.flattenToString()
    }

    @Test
    fun removedClinicalValuesAreAbsentFromSavedState() {
        val payload = persistedPayload()

        assertFalse(
            "Emergency threshold reason text must not reach saved state; it is re-derived from " +
                "the persisted vitals row instead",
            payload.contains(EMERGENCY_REASON_PROBE),
        )
        assertFalse(
            "An audio attachment path must not reach saved state; it is read from the " +
                "consultation's attachment row instead",
            payload.contains(AUDIO_PATH_PROBE),
        )
        assertFalse(
            "No route should still declare an audioUri argument",
            payload.contains("audioUri"),
        )
        assertFalse(
            "No route should still declare a reasons argument",
            payload.contains("reasons"),
        )
    }

    @Test
    fun opaqueIdsArePresentSoTheAssertionsAreNotVacuous() {
        val payload = persistedPayload()

        assertTrue(payload.contains("pat-1"))
        assertTrue(payload.contains("enc-1"))
        assertTrue(payload.contains("case-1"))
        assertTrue(payload.contains("consult-1"))
    }

    /**
     * DELIBERATELY RETAINED, phase 1. Each of these is removed from saved state by a phase 2 pop
     * policy, not by reshaping its route. See the class KDoc before changing any of them.
     */
    @Test
    fun valuesDeferredToPhaseTwoAreStillPresentOnPurpose() {
        val payload = persistedPayload()

        // ConsultationRoute.chiefComplaint. Clinical free text. It exists nowhere in the database
        // between Compounder and the consultation save, so it cannot be re-read; a draft
        // consultations row was considered and rejected. Phase 2 pops this route to Compounder.
        assertTrue(
            "chiefComplaint is retained this phase by decision, not by omission",
            payload.contains(COMPLAINT_PROBE),
        )

        // AbhaCreateOtpRoute.maskedMobile. It originates in another NavEntry's ViewModelStore, so
        // dropping the argument would delete the "code sent to the mobile ending XXXX" line on
        // the forward path, not just after a restore. Phase 2 pops this route to AbhaAadhaarEntry.
        assertTrue(
            "maskedMobile is retained this phase by decision, not by omission",
            payload.contains(MASKED_MOBILE_PROBE),
        )

        // AbhaOtpRoute / Register / AbhaProfileRoute abhaId. A national health identifier, and at
        // those points there is no local patient row to key on instead. Phase 2 settles this as
        // memo decision D2.
        assertTrue(
            "abhaId is retained this phase by decision, not by omission",
            payload.contains(ABHA_PROBE),
        )
    }
}
