package com.example.samdapp.config

/**
 * Central on/off switches for behavior that gets in the way during development/debugging
 * (ADB automation, screen recording a demo, etc.) but must ship enabled. Flip a value here and
 * rebuild — no UI, no remote config, just a recompile.
 */
object FeatureFlags {
    /** FLAG_SECURE on patient-data screens (blocks screenshots/screen-recording, blanks the
     *  recent-apps thumbnail). Flip to false locally for on-device debugging via
     *  `adb screencap`/`uiautomator` — must stay true in anything committed/shipped (CI's
     *  RoutesSecurityTest enforces this). See
     *  [com.example.samdapp.presentation.navigation.requiresScreenSecurity]. */
    const val SCREEN_SECURITY_ENABLED = true

    /** Idle auto-lock (75s) with biometric re-auth, drawn over the nav host.
     *  Off = app never locks on idle. See [com.example.samdapp.presentation.navigation.AppNavHost]. */
    const val IDLE_LOCK_ENABLED = true

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
}
