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
     *  so [com.example.samdapp.data.transcription.AndroidSpeechRecognizerService] is never reached.
     *
     *  Left off pending the sherpa-onnx on-device engine: the current implementation calls
     *  Android's platform `SpeechRecognizer` via `createSpeechRecognizer` (not
     *  `createOnDeviceSpeechRecognizer`), never sets `EXTRA_PREFER_OFFLINE`, and the app holds
     *  `INTERNET`, so patient narrative may leave the device to a third-party recognizer with no
     *  data-processing agreement and no coverage under H-11. See
     *  `scratchpad/asr-usecase-research-memo.md` Task 0 and
     *  `scratchpad/asr-field-audit-memo.md` C-1/DECISION GATE item 5.
     *
     *  See [com.example.samdapp.presentation.consultation.ConsultationScreen]. */
    const val VOICE_INPUT_ENABLED = false
}
