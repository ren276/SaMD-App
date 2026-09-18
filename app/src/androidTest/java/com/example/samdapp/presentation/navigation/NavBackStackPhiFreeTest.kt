package com.example.samdapp.presentation.navigation

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.savedstate.SavedState
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * V4. The regression guard for the ids-only route reshape and the save-time transform.
 *
 * The saved back stack lands in the Activity's `onSaveInstanceState` Bundle, which is process
 * memory held by the system server: not SQLCipher, not `samd_document_key`, none of the
 * protections the rest of this app's PHI sits behind.
 *
 * READ THIS BEFORE "FIXING" A FAILURE HERE. The test asserts two directions at once, and both
 * halves are load-bearing:
 *
 *  - no probe value reaches the serialized Bundle, AND
 *  - each transformed entry is replaced by its correct safe ANCESTOR.
 *
 * The second half is what makes the transform correct rather than merely safe. Without it the
 * suite would stay green if someone "fixed" a leak by transforming the whole stack to `[Home]`,
 * which leaks nothing and destroys the resumption behaviour this track exists to add.
 *
 * The fixture keeps every route, including the three that still declare human-readable arguments.
 * Those arguments are needed on the FORWARD path and cannot be dropped from the routes; they are
 * kept out of the Bundle by `transformForSave`. Do not make a failure here pass by deleting a
 * route from the fixture: the routes being present while their values are absent is the entire
 * proof.
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

    /**
     * Every route that carries an argument, so nothing escapes the assertions by not being here,
     * plus the safe ancestors the transform's drops rely on.
     *
     * The ancestors are not padding. Each unsafe route that is DROPPED rather than replaced is
     * dropped precisely because the entry it was pushed from is already in the stack, and a
     * fixture without them would assert against a stack shape the app never produces:
     * `AbhaAadhaarEntry` pushes `AbhaCreateOtpRoute` (AppNavHost.kt:227), `AbhaLogin` pushes
     * `AbhaOtpRoute` (:242), and `PatientSummary` pushes `AbhaProfileRoute` (:277).
     *
     * Adding them strengthens this test rather than relaxing it: every route that declares a
     * human-readable argument is still here, still carrying its probe.
     */
    private fun everyArgumentCarryingRoute() = NavBackStack<NavKey>(
        Home,
        PatientSummary("pat-1"),
        MedicalBackground("pat-1"),
        PatientAuditRoute("pat-1"),
        DocumentViewerRoute("doc-1"),
        AbhaProfileRoute(ABHA_PROBE),
        AbhaEntry,
        AbhaLogin,
        AbhaOtpRoute(ABHA_PROBE),
        AbhaAadhaarEntry,
        AbhaCreateOtpRoute("session-1", MASKED_MOBILE_PROBE),
        Register(ABHA_PROBE),
        ConsentRoute("pat-1", followUpOfEncounterId = "enc-0"),
        Compounder("pat-1", "enc-0", "enc-1", "case-1"),
        ConsultationRoute("pat-1", "enc-1", "case-1", COMPLAINT_PROBE),
        EmergencyOverrideRoute("pat-1", "enc-1"),
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
    }

    /**
     * The half that the save-time transform added. Each of these three values is still declared by
     * a route in the fixture, because the forward path needs it, and none of them reaches the
     * Bundle.
     */
    @Test
    fun humanReadableArgumentsDoNotReachSavedStateEvenThoughTheirRoutesDeclareThem() {
        val payload = persistedPayload()

        // ConsultationRoute.chiefComplaint: clinical free text, absent nowhere in the database
        // between Compounder and the consultation save, so the route has to carry it forward.
        // The transform replaces the entry with Compounder in resume shape.
        assertFalse(
            "chiefComplaint must not reach the Bundle; the entry transforms to Compounder",
            payload.contains(COMPLAINT_PROBE),
        )

        // AbhaCreateOtpRoute.maskedMobile: originates in another NavEntry's ViewModelStore, so the
        // argument cannot be dropped without losing the "code sent to the mobile ending XXXX" line
        // on the forward path. The transform drops the whole entry to AbhaAadhaarEntry.
        assertFalse(
            "maskedMobile must not reach the Bundle; the entry transforms to AbhaAadhaarEntry",
            payload.contains(MASKED_MOBILE_PROBE),
        )

        // abhaId on AbhaOtpRoute / Register / AbhaProfileRoute: a national health identifier.
        assertFalse(
            "abhaId must not reach the Bundle from any of the three routes that declare it",
            payload.contains(ABHA_PROBE),
        )
    }

    /**
     * THE OTHER HALF, and the reason this test cannot be satisfied by nuking the stack. Every
     * transformed entry must land on its correct safe ancestor. A transform that emptied the stack
     * to `[Home]` would pass every absence assertion above and would have destroyed the whole
     * point of a restorable back stack.
     */
    @Test
    fun eachTransformedEntryLandsOnItsSafeAncestor() {
        val saved = navBackStackSaver(ownerUserId = "worker-a").saveStack(everyArgumentCarryingRoute())
        val restored = navBackStackSaver(ownerUserId = "worker-a").restore(saved!!)!!.toList()

        assertTrue("The stack must survive, not be emptied", restored.size > 5)
        assertTrue("Home is still the root", restored.first() == Home)

        // ConsultationRoute -> Compounder in resume shape, carrying the case it was about.
        val compounder = restored.filterIsInstance<Compounder>().single { it.resumeCaseRecordId == "case-1" }
        assertEquals("enc-1", compounder.resumeEncounterId)

        // TranscriptionRoute -> AcknowledgementRoute for the same case, the same branch
        // KernelAssessmentScreen already takes when there is no audio.
        assertTrue(
            "TranscriptionRoute must become AcknowledgementRoute for its own case",
            restored.any { it is AcknowledgementRoute && it.caseRecordId == "case-1" },
        )

        // The enrolment leg restarts from the one step that is always safe to redo.
        assertTrue("AbhaCreateOtpRoute must land on AbhaAadhaarEntry", restored.contains(AbhaAadhaarEntry))
        assertTrue("Register(abhaId) must land on AbhaEntry", restored.contains(AbhaEntry))

        // And none of the unsafe routes themselves survive.
        assertTrue(restored.none { it is ConsultationRoute })
        assertTrue(restored.none { it is TranscriptionRoute })
        assertTrue(restored.none { it is AbhaCreateOtpRoute })
        assertTrue(restored.none { it is AbhaOtpRoute })
        assertTrue(restored.none { it is AbhaProfileRoute })
        assertTrue(restored.none { it is Register })

        // EmergencyOverrideRoute is deliberately NOT transformed: ids only after phase 1, and the
        // worker must still see the red flag on return.
        assertTrue(
            "EmergencyOverrideRoute must survive untouched",
            restored.contains(EmergencyOverrideRoute("pat-1", "enc-1")),
        )
    }

    /**
     * R1's decoupling, proved. The transform must not depend on the in-place entry rewrite in
     * `AppNavHost` having run: a stack whose Compounder is still in START shape must still come
     * back with a resume-shape ancestor, because a start shape restored after the case exists
     * re-runs StartCaseUseCase and mints a second encounter.
     */
    @Test
    fun aConsultationRouteAboveAStartShapeCompounderStillYieldsAResumeShape() {
        val neverRewritten = NavBackStack<NavKey>(
            Home,
            Compounder("pat-1"),
            ConsultationRoute("pat-1", "enc-1", "case-1", COMPLAINT_PROBE),
        )

        val saved = navBackStackSaver(ownerUserId = "worker-a").saveStack(neverRewritten)
        val restored = navBackStackSaver(ownerUserId = "worker-a").restore(saved!!)!!.toList()

        assertEquals(listOf(Home, Compounder("pat-1", null, "enc-1", "case-1")), restored)
        assertFalse(saved.flattenToString().contains(COMPLAINT_PROBE))
    }
}
