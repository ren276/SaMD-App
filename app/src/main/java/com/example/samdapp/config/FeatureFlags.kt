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

    /** The mic affordance on `chiefComplaint` only (the "Voice" mode toggle and the "Record main
     *  concern" button). Off = the controls are hidden, not merely disabled, and
     *  [com.example.samdapp.presentation.consultation.ConsultationViewModel.onRecordChiefComplaintVoice]
     *  returns before invoking [com.example.samdapp.domain.usecase.CaptureAudioAttachmentUseCase],
     *  so [com.example.samdapp.data.transcription.SherpaOnnxTranscriptionService] is never reached
     *  for this field and its model is never loaded on this path.
     *
     *  **Split out of the retired single voice flag** (2026-09-08,
     *  `scratchpad/chief-complaint-voice-flip-design-memo.md` section 5). That flag gated two
     *  affordances with different missing controls and no way to enable one without the other:
     *  this field mic, and the audio attachment plus its auto-transcribe path
     *  ([VOICE_AUDIO_ATTACHMENT_ENABLED]). The split is a refactor only. Both halves stay `false`,
     *  so nothing a worker can reach changed at the split.
     *
     *  Stays `false`: `chiefComplaint` is the **High**-severity H-15 path that reaches
     *  `/api/v1/evaluate`, it is governed by no confirmation gate (unlike
     *  [VOICE_FIELD_IMPACT_ENABLED], which has one), it has no persisted
     *  [com.example.samdapp.domain.model.FieldProvenance] column, and per the memo's section 2 the
     *  live evaluate path applies no abstention of any kind to the text it is given. Flipping this
     *  is not a flag change: it requires the confirmation gate, a provenance migration across two
     *  repositories, and its own memo (memo section 9, conditions X/Y/Z).
     *
     *  See [com.example.samdapp.presentation.consultation.ConsultationScreen]. */
    const val VOICE_FIELD_CHIEF_COMPLAINT_ENABLED = false

    /** The audio attachment ("Record audio" on the Consultation screen) **and** the transcription
     *  path it feeds. Off = the button is hidden, not merely disabled;
     *  [com.example.samdapp.presentation.consultation.ConsultationViewModel.onRecordAudioAttachment]
     *  returns before capturing; the `KernelAssessment` screen never routes to
     *  [com.example.samdapp.presentation.navigation.TranscriptionRoute]; and
     *  [com.example.samdapp.domain.usecase.TranscribeAudioUseCase] refuses before it can transcribe
     *  or persist anything.
     *
     *  **Split out of the retired single voice flag, and it is the half that needed splitting**
     *  (`scratchpad/chief-complaint-voice-flip-design-memo.md` section 3.1(ii), registered as risk
     *  row H-15.C2). This path writes a raw ASR transcript straight into
     *  `Consultation.transcription` via `updateTranscription`, with no confirmation gate, no
     *  provenance column, and an explicit exemption from the `VOICE_UNCONFIRMED` write refusal in
     *  [com.example.samdapp.data.repository.ConsultationRepositoryImpl]. That value is then
     *  concatenated into `symptom_string` by
     *  [com.example.samdapp.data.remote.RetrofitEvaluateSource] and reaches the model. The
     *  Transcription screen shows the transcript only after it has already been persisted, and its
     *  only affordance is Continue.
     *
     *  While the two affordances shared one flag there was no way to enable the gated field
     *  without enabling this ungated path in the same build. That is why this flag exists
     *  separately, and why the guard in [com.example.samdapp.domain.usecase.TranscribeAudioUseCase]
     *  exists in addition to the navigation and capture guards: the persist has to be unreachable
     *  from any caller, not just from the screen that has one today.
     *
     *  Stays `false`. Making it flippable is the parked build (confirmation gate plus provenance),
     *  not a flag change.
     *
     *  See [com.example.samdapp.presentation.consultation.ConsultationScreen]. */
    const val VOICE_AUDIO_ATTACHMENT_ENABLED = false

    /** The voice confirmation gate on `impactOnDailyActivities` only (mic button and the
     *  suggestion surface). Independent of [VOICE_FIELD_CHIEF_COMPLAINT_ENABLED] and
     *  [VOICE_AUDIO_ATTACHMENT_ENABLED], which both stay `false` and gate the **High**-severity
     *  H-15 paths that reach `/api/v1/evaluate`.
     *
     *  **Flipped `true` — commit 5, authorized.** Transmission proof complete: L2.3 (reflection
     *  scan, no platform recognizer in bytecode), L3.1 (decode produces real output), L3.2
     *  (txDelta=0B / rxDelta=0B across a full decode), L3.3 (StrictMode — no network call) all
     *  green on x86_64 emulator. L3.4 (capture-writes-no-file) relocated to the arm64 iQOO
     *  I2302 operator run (real mic, real ABI), same trigger as L3.5. Pre-distribution gate
     *  list: L3.4 + L3.5 on iQOO I2302 (one arm64 run closes both), CC-BY-4.0 attribution
     *  discharged by PR 4b-1.
     *
     *  Outstanding non-code item: model trained on read, predominantly US-accented English;
     *  no Indian-accented evaluation has been run.
     *
     *  See [com.example.samdapp.presentation.consultation.ConsultationScreen]. */
    const val VOICE_FIELD_IMPACT_ENABLED = true

    /** The voice confirmation gate on `aggravatingFactors` only (mic button and its suggestion
     *  surface). Independent of every other `VOICE_FIELD_*` flag and of
     *  [VOICE_AUDIO_ATTACHMENT_ENABLED],
     *  same posture as [VOICE_FIELD_IMPACT_ENABLED] at its introduction (PR 3c): default `false`,
     *  not yet transmission-proofed for this field.
     *
     *  Unlike [VOICE_FIELD_IMPACT_ENABLED], a confirmed or edited value here is not stamped with a
     *  persisted [com.example.samdapp.domain.model.FieldProvenance] column - `aggravatingFactors`
     *  has no such column, and adding one is a migration/wire-contract change out of scope for
     *  this flag's introduction (PR5, voice field-expansion). The four `VOICE_FIELD_*` audit
     *  breadcrumbs still fire on every transition; the audit log is this field's provenance
     *  record, not the database row.
     *
     *  See [com.example.samdapp.presentation.consultation.ConsultationScreen]. */
    const val VOICE_FIELD_AGGRAVATING_ENABLED = false

    /** The voice confirmation gate on `relievingFactors` only. Same posture and same DB-provenance
     *  asymmetry as [VOICE_FIELD_AGGRAVATING_ENABLED] - see its KDoc.
     *
     *  See [com.example.samdapp.presentation.consultation.ConsultationScreen]. */
    const val VOICE_FIELD_RELIEVING_ENABLED = false

    /** The voice confirmation gate on `relevantHistory` only. Same posture and same DB-provenance
     *  asymmetry as [VOICE_FIELD_AGGRAVATING_ENABLED] - see its KDoc.
     *
     *  See [com.example.samdapp.presentation.consultation.ConsultationScreen]. */
    const val VOICE_FIELD_RELEVANT_HISTORY_ENABLED = false

    /** Hides the AI treatment recommendation (`EvaluateReportOutput`) from the worker-facing
     *  report, and hides an AI-agreed/AI-modified/AI-rejected prescription's medication lines on
     *  a doctor REJECT, until a physician decision has been committed for the case (AGREE /
     *  MODIFY / REJECT via [com.example.samdapp.domain.usecase.SubmitDoctorDecisionUseCase]).
     *  Also gates the `DoctorReviewCard` decision surface on
     *  [com.example.samdapp.presentation.patientsummary.PatientSummaryScreen] to
     *  `UserRole.DOCTOR` — see `PatientSummaryUiState.canOpenDoctorReview` — so flipping this
     *  flag off restores the pre-gate demo behaviour (any role can open and submit the review) in
     *  one place.
     *
     *  Off = prior behaviour: the `/api/v1/evaluate` treatment block and brand suggestion render
     *  on the worker report unconditionally as soon as evaluate returns, a REJECTed case's manual
     *  medication line renders like any other, and the decision surface has no role check.
     *
     *  Default ON: hidden-until-approved is the safe default. See
     *  [com.example.samdapp.domain.report.ReportFormatter]. */
    const val PRESCRIPTION_APPROVAL_GATE_ENABLED = true
}
