package com.example.samdapp.presentation.navigation

import androidx.navigation3.runtime.NavKey
import com.example.samdapp.config.FeatureFlags
import kotlinx.serialization.Serializable

/*
 * Every route is `@Serializable` and implements [NavKey] so the back stack itself can be saved
 * and restored across process death (see `AppNavHost`'s `navBackStackSaver`). Two consequences
 * that are easy to miss when adding a route:
 *
 *  1. A route's fully qualified class name IS the persisted wire format. `NavKeySerializer`
 *     writes `value::class.java.name` and reads it back with `Class.forName(...)`, so renaming
 *     or moving a route class silently invalidates every stack saved by an older build. That is
 *     why `app/proguard-rules.pro` keeps this package.
 *  2. Every argument declared here would be written into the Activity's saved-state Bundle, which
 *     is NOT covered by SQLCipher or the document key. Arguments are opaque local ids by default.
 *     The three exceptions still carrying human-readable values are marked below. They are kept
 *     out of the Bundle by the SAVE-TIME transform in `NavBackStackSaver.transformForSave`, not
 *     by carrying less here and not by anything that happens at restore: `onSaveInstanceState`
 *     writes the Bundle before the process dies, so a policy applied on the way back in decides
 *     only what the worker returns to and removes nothing that was written.
 */

@Serializable
data object Home : NavKey

/** Bottom-nav tab: searchable roster beyond just today (see [Patients] entry in AppNavHost). */
@Serializable
data object Patients : NavKey

/** Bottom-nav tab: this device's own sent-referral outbox. */
@Serializable
data object Referrals : NavKey

/** Bottom-nav tab: signed-in worker's session info, audit summary, sync toggle, sign-out. */
@Serializable
data object Profile : NavKey

/** Attribution screen for third-party/SOUP components (Parakeet CC BY 4.0 obligation and the
 *  app's other dependency licences), reached from [Profile]'s "Open-source licences" button. */
@Serializable
data object OpenSourceLicensesRoute : NavKey

@Serializable
data object AbhaEntry : NavKey

@Serializable
data object AbhaSignUp : NavKey

@Serializable
data object AbhaLogin : NavKey

@Serializable
data class AbhaOtpRoute(val abhaId: String) : NavKey

/** Aadhaar step of the real ABDM-backed create flow, reached from [AbhaEntry]'s "Create ABHA ID".
 *  Takes no arguments: the registration session does not exist until this screen starts one. */
@Serializable
data object AbhaAadhaarEntry : NavKey

/**
 * OTP step of the real create flow. [sessionId] is the backend's local registration-transaction
 * id and the only handle carried across steps, exactly as [AbhaOtpRoute] carries `abhaId` — there
 * is no client-side ABDM `txnId` in this contract, so nothing here is redaction-sensitive.
 * [maskedMobile] is ABDM's already-masked rendering of the Aadhaar-linked number (`XXXXXX3210`),
 * carried because no endpoint returns it a second time and it is display-only.
 *
 * SAVED-STATE NOTE: [maskedMobile] is deliberately RETAINED as a route argument even though it is
 * a contact quasi-identifier. It originates in [AbhaAadhaarEntryViewModel]'s effect, which lives
 * in a different NavEntry's ViewModelStore, so dropping the argument would delete the "code sent
 * to the mobile ending XXXX" line on the FORWARD path, not just after a restore. It never reaches
 * the Bundle because the save-time transform drops this whole entry to [AbhaAadhaarEntry], which
 * also discards a [sessionId] whose backend registration transaction is time-bounded.
 * `NavBackStackPhiFreeTest` asserts the value is absent from the serialized stack while this route
 * is still in its fixture; do not "fix" that by dropping the argument here.
 */
@Serializable
data class AbhaCreateOtpRoute(val sessionId: String, val maskedMobile: String?) : NavKey

/** [abhaId] is null for a manual/no-ABHA registration ("Skip" on [AbhaEntry]); non-null when
 *  reached via the mock ABHA sign-up/login flow, so [RegisterViewModel] autofills from it.
 *
 *  SAVED-STATE NOTE: [abhaId] is a national health identifier. Retained as an argument, because
 *  the forward path needs it to autofill; kept out of the Bundle by the save-time transform, which
 *  replaces `Register(abhaId != null)` with [AbhaEntry]. `Register(null)` carries no identifier and
 *  is left alone. */
@Serializable
data class Register(val abhaId: String? = null) : NavKey

@Serializable
data class MedicalBackground(val patientId: String) : NavKey

@Serializable
data class PatientSummary(val patientId: String) : NavKey

/** Read-only ABHA profile view, reached from [PatientSummary]'s ABHA row (shown only when
 *  `patient.abhaNumber != null`). NOT a bottom-nav tab and NOT named `Profile`/`ProfileScreen` —
 *  those already name the signed-in worker's own profile screen ([Profile] above).
 *
 *  SAVED-STATE NOTE: see [Register]. Same deferral, same reason. */
@Serializable
data class AbhaProfileRoute(val abhaId: String) : NavKey

/** Digital consent checkpoint (REQ-TRS-01) — shown once, before Compounder (where ailment
 *  capture begins). [followUpOfEncounterId] carries the worker's optional "this is a follow-up
 *  to a prior visit" pick from PatientSummary's consultation history (Part C) through to
 *  [Compounder]/[com.example.samdapp.domain.usecase.StartCaseUseCase], which stamps it onto the
 *  new [com.example.samdapp.domain.model.Encounter]. */
@Serializable
data class ConsentRoute(val patientId: String, val followUpOfEncounterId: String? = null) : NavKey

/** [resumeEncounterId]/[resumeCaseRecordId] are set only when reached via Home's crash-recovery
 *  "resume in-progress consultation" prompt — non-null in both or null in both. Consent was already
 *  recorded for that encounter, so resuming skips straight back into Compounder rather than
 *  re-running [com.example.samdapp.domain.usecase.StartCaseUseCase], which would mint a second
 *  encounter/case record for the same visit. */
@Serializable
data class Compounder(
    val patientId: String,
    val followUpOfEncounterId: String? = null,
    val resumeEncounterId: String? = null,
    val resumeCaseRecordId: String? = null,
) : NavKey

/**
 * Terminal state reached from [Compounder] when [com.example.samdapp.domain.usecase.
 * CheckEmergencyThresholdsUseCase] trips (REQ-TRS-02).
 *
 * Carries ids only. The tripped threshold reasons used to be passed as a `List<String>` of
 * clinical text; they are now re-derived by [EmergencyOverrideViewModel] from the vitals already
 * persisted for [encounterId], because `CompounderViewModel` records the vitals BEFORE it runs
 * the threshold check on those same values, and the check is a pure function of three of them.
 * The re-derived text is therefore identical, not approximate.
 *
 * [encounterId], not `caseRecordId`: vitals are keyed on the encounter
 * ([com.example.samdapp.domain.repository.VitalsRepository.observeLatestForEncounter]) and
 * [com.example.samdapp.domain.model.VitalsSnapshot] has no case id at all.
 *
 * [patientId] is carried for the re-derivation's own use. It is deliberately NOT added to
 * [currentPatientId]'s scan: the patient banner already resolves from the [Compounder] entry
 * beneath this one, and that behaviour is unchanged.
 */
@Serializable
data class EmergencyOverrideRoute(val patientId: String, val encounterId: String) : NavKey

/**
 * SAVED-STATE NOTE: [chiefComplaint] is clinical free text and is deliberately RETAINED as a
 * route argument. It exists nowhere in the database between Compounder and the consultation save
 * (a draft `consultations` row was considered and rejected: it needed a one-row-per-encounter
 * migration on a clinical table), so it cannot be re-read and the forward hop has to carry it.
 * It never reaches the Bundle because the save-time transform replaces this entry with
 * [Compounder] in resume shape, built from this route's own [patientId]/[encounterId]/
 * [caseRecordId] rather than from whatever happens to sit beneath it.
 */
@Serializable
data class ConsultationRoute(
    val patientId: String,
    val encounterId: String,
    val caseRecordId: String,
    val chiefComplaint: String,
) : NavKey

@Serializable
data class SendingRoute(
    val caseRecordId: String,
    val consultationId: String,
    val encounterId: String,
) : NavKey

/** AI Assessment Panel (REQ-HAN-07) — shown right after the mocked kernel handoff, before the
 *  case proceeds to transcription/save. The audio attachment's uri is no longer carried here;
 *  [com.example.samdapp.presentation.kernelassessment.KernelAssessmentViewModel] resolves it from
 *  the attachment row for [consultationId], which is the row that decides whether audio exists at
 *  all. */
@Serializable
data class KernelAssessmentRoute(
    val caseRecordId: String,
    val consultationId: String,
) : NavKey

/** The audio uri is resolved from [consultationId]'s attachment row rather than carried: a uri
 *  that survived three screens is not evidence the attachment was persisted, the row is. */
@Serializable
data class TranscriptionRoute(val consultationId: String, val caseRecordId: String) : NavKey

@Serializable
data class AcknowledgementRoute(val caseRecordId: String) : NavKey

/** Preliminary clinical report (Phase 3) — worker-facing preview + PDF export, reached from the
 *  Acknowledgement screen once the case is saved locally. Reused read-only for past-encounter
 *  history (Part C) and the doctor-tracker (Part B) — this screen has no edit affordances on the
 *  report content itself (the referral sheet is a separate, additive action). */
@Serializable
data class ReportRoute(val caseRecordId: String) : NavKey

/** A single patient's follow-up chain — every visit linked to [rootEncounterId], newest first,
 *  each opening its own single-consult [ReportRoute]. Keeps the Consultation History list clean
 *  (one row per chain) while still letting the worker drill into all of a chain's visits. Reports
 *  are never merged; each visit is its own document. */
@Serializable
data class ConsultationChainRoute(val patientId: String, val rootEncounterId: String) : NavKey

/** Bottom-nav-adjacent, cross-patient, read-only status tracker (Part B) — every case currently
 *  sent to a doctor, across all patients. No longer the per-case doctor picker (that step is now
 *  automatic — see [DoctorAssignmentConfirmRoute] for the one case where a worker still chooses:
 *  confirming/switching a continuity-of-care doctor on a follow-up visit). */
@Serializable
data object DoctorListRoute : NavKey

/** Narrow doctor-continuity confirmation (Part B) — shown only when the new case is a follow-up
 *  to a prior visit with a resolved doctor; a fresh/unrelated case is auto-assigned silently and
 *  never reaches this screen. */
@Serializable
data class DoctorAssignmentConfirmRoute(val caseRecordId: String) : NavKey

/** DPDP right-to-access gesture: a patient-presentable, plain-language view of who has touched
 *  their record, reached from PatientSummary's "Who has seen your file" action. */
@Serializable
data class PatientAuditRoute(val patientId: String) : NavKey

/** H-18, Build 3a: the safe in-app viewer for one consultation document — decrypts to a
 *  `cacheDir` temp file for [android.graphics.pdf.PdfRenderer]/`BitmapFactory`, never an external
 *  handler. Registered in [SECURED_ROUTE_TYPES] so `FLAG_SECURE` applies in staging/prod. */
@Serializable
data class DocumentViewerRoute(val documentId: String) : NavKey

/**
 * Routes that show patient-identifying or clinical data — [FLAG_SECURE][android.view.WindowManager.LayoutParams.FLAG_SECURE]
 * is applied for all of these (blocks screenshots/screen-recording, blanks the recent-apps
 * thumbnail) since this is a single-Activity app and the flag is window-wide, not per-screen.
 * Deliberately excludes [Home]/[Login]/[Patients] (names alone, lower sensitivity — the point
 * isn't to block screenshots there)/[AbhaEntry] (no data yet, just a menu)/[Profile]/
 * [OpenSourceLicensesRoute] (static attribution text, no patient data).
 */
private val SECURED_ROUTE_TYPES: Set<Class<out Any>> = setOf(
    AbhaSignUp::class.java,
    AbhaLogin::class.java,
    AbhaOtpRoute::class.java,
    // The Aadhaar screen shows a full Aadhaar number and the create-OTP screen shows a live OTP
    // plus the patient's communication mobile. Both are squarely what this list is for.
    AbhaAadhaarEntry::class.java,
    AbhaCreateOtpRoute::class.java,
    Register::class.java,
    MedicalBackground::class.java,
    PatientSummary::class.java,
    PatientAuditRoute::class.java,
    AbhaProfileRoute::class.java,
    ConsentRoute::class.java,
    Compounder::class.java,
    EmergencyOverrideRoute::class.java,
    ConsultationRoute::class.java,
    SendingRoute::class.java,
    KernelAssessmentRoute::class.java,
    TranscriptionRoute::class.java,
    AcknowledgementRoute::class.java,
    DoctorAssignmentConfirmRoute::class.java,
    ConsultationChainRoute::class.java,
    ReportRoute::class.java,
    DoctorListRoute::class.java,
    Referrals::class.java,
    DocumentViewerRoute::class.java,
)

fun requiresScreenSecurity(route: Any?): Boolean =
    FeatureFlags.SCREEN_SECURITY_ENABLED && route != null && route.javaClass in SECURED_ROUTE_TYPES

/**
 * The patient the current back-stack entry is about, or null if none (Home, Register).
 * Scans from the top down so routes that don't carry a patientId (Sending, Transcription,
 * Acknowledgement, DoctorList) inherit it from the encounter route beneath them — keeping
 * the patient identity banner visible for the whole encounter.
 */
/**
 * True when some entry in [backStack] is already about [caseRecordId].
 *
 * Home's crash-recovery prompt is gated on this. A restored deep stack has `Home` at index 0 and
 * an encounter screen above it, so the moment the worker backs out to Home mid-encounter the
 * prompt would otherwise offer to resume the exact case they are already inside, and accepting
 * pushes a SECOND [Compounder] entry for it. That is the consult-sent-to-two-doctors defect class
 * reached from a new direction.
 *
 * The invariant this enforces, together with `dismissedResumeId` being saveable: at most one live
 * path to a given case exists at any time, whether it arrived by restore or by the resume prompt.
 * That invariant is also what keeps the `Compounder` entry's pinned content key unique in
 * `AppNavHost`; weakening this gate is not a tidiness regression there, it is a wrong-encounter
 * binding. Read that comment before touching this.
 */
fun backStackContainsCase(backStack: List<Any>, caseRecordId: String): Boolean =
    backStack.any { route ->
        when (route) {
            is Compounder -> route.resumeCaseRecordId == caseRecordId
            is ConsultationRoute -> route.caseRecordId == caseRecordId
            is SendingRoute -> route.caseRecordId == caseRecordId
            is KernelAssessmentRoute -> route.caseRecordId == caseRecordId
            is TranscriptionRoute -> route.caseRecordId == caseRecordId
            is AcknowledgementRoute -> route.caseRecordId == caseRecordId
            is ReportRoute -> route.caseRecordId == caseRecordId
            is DoctorAssignmentConfirmRoute -> route.caseRecordId == caseRecordId
            else -> false
        }
    }

fun currentPatientId(backStack: List<Any>): String? {
    for (route in backStack.asReversed()) {
        when (route) {
            is MedicalBackground -> return route.patientId
            is PatientSummary -> return route.patientId
            is ConsentRoute -> return route.patientId
            is Compounder -> return route.patientId
            is ConsultationRoute -> return route.patientId
            is ConsultationChainRoute -> return route.patientId
            is PatientAuditRoute -> return route.patientId
        }
    }
    return null
}
