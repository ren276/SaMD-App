package com.example.samdapp.config

import com.example.samdapp.BuildConfig

object FeatureFlags {
    /** FLAG_SECURE on patient-data screens (blocks screenshots/screen-recording, blanks the
     *  recent-apps thumbnail). Flavor-gated in build.gradle.kts: off for dev (investor/demo
     *  screen recordings), on for staging/prod.
     *  See
     *  [com.example.samdapp.presentation.navigation.requiresScreenSecurity]. */
    val SCREEN_SECURITY_ENABLED = BuildConfig.SCREEN_SECURITY_ENABLED

    /** Idle auto-lock (75s) with biometric re-auth, drawn over the nav host.
     *  Off = app never locks on idle. See [com.example.samdapp.presentation.navigation.AppNavHost]. */
    const val IDLE_LOCK_ENABLED = false
    /** Patient-facing "who has seen your file" audit trail entry point on the patient summary
     *  screen. Off = button hidden, screen unreachable.
     *  See [com.example.samdapp.presentation.patientsummary.PatientSummaryScreen]. */
    const val PATIENT_AUDIT_ENABLED = true

    /** Crash-recovery resume prompt on Home for an in-progress DRAFT case.
     *  Off = no prompt; worker re-enters the draft manually via Patients.
     *  See [com.example.samdapp.presentation.home.HomeScreen]. */
    const val RESUME_DRAFT_ENABLED = true

    /** Low battery/storage nudge before starting a consultation or registering a patient.
     *  Off = skip the check, proceed directly.
     *  See [com.example.samdapp.presentation.common.DeviceResourceCheck]. */
    const val DEVICE_RESOURCE_CHECK_ENABLED = true

    /** Voice-to-text affordances on the Consultation screen ("Voice" mode toggle, "Record main
     *  concern", "Record audio" attachment). Off = the controls are hidden, not merely disabled,
     *  and their handlers return before invoking [com.example.samdapp.domain.usecase.CaptureAudioAttachmentUseCase]
     *  so [com.example.samdapp.data.transcription.SherpaOnnxTranscriptionService] is never
     *  reached and its model is never loaded.
     *
     *  The original reason for this flag is gone: the off-device transmission risk it was holding
     *  the line against belonged to the platform speech recognizer, which has been deleted. It
     *  stays off because these are the **High**-severity H-15 paths that reach
     *  `/api/v1/evaluate`, and they are governed by no confirmation gate — unlike
     *  [VOICE_FIELD_IMPACT_ENABLED], which has one. See
     *  `scratchpad/asr-usecase-research-memo.md` Task 0 and
     *  `scratchpad/asr-field-audit-memo.md` C-1/DECISION GATE item 5.
     *
     *  See [com.example.samdapp.presentation.consultation.ConsultationScreen]. */
    const val VOICE_INPUT_ENABLED = false

    /** The voice confirmation gate on `impactOnDailyActivities` only (mic button and the
     *  suggestion surface). Independent of [VOICE_INPUT_ENABLED], which stays `false` and gates
     *  `chiefComplaint` voice plus the audio attachment, the **High**-severity H-15 paths that
     *  reach `/api/v1/evaluate`. Off = the mic is hidden and the suggestion surface never renders,
     *  same "hidden, not merely disabled" discipline as [VOICE_INPUT_ENABLED].
     *
     *  The on-device engine is now bound (`SherpaOnnxTranscriptionService`) and the platform
     *  recognizer is deleted, which closes the transmission half of the argument for keeping this
     *  off. It stays `false` regardless, deliberately: "no off-device ASR path remains" is at this
     *  point a claim about the code, and the flag does not flip on a claim. It flips in the change
     *  that lands the evidence — the source-level absence scan and the `StrictMode` egress
     *  assertion — and not before (`scratchpad/pr4-sherpa-onnx-design-memo.md` Part C.2/C.3).
     *  Still outstanding at that point, and not a code matter: the model is trained on read,
     *  predominantly US-accented English, and no Indian-accented evaluation has been run.
     *
     *  See [com.example.samdapp.presentation.consultation.ConsultationScreen]. */
    const val VOICE_FIELD_IMPACT_ENABLED = false
}
