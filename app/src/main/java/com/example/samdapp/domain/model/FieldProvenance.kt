package com.example.samdapp.domain.model

/** Per-field provenance for a voice-fillable clinical field (ASR track, PR 1 of the sequence in
 *  `scratchpad/asr-field-audit-memo.md` Part B.2, "Provenance stamping, mirroring the
 *  vitals-provenance property"). Mirrors [InferenceSource]'s stamped-once discipline: recorded
 *  once, at the exact point a value is committed, so it can never drift out of sync with how that
 *  value actually got there.
 *
 *  - [TYPED]: the worker typed it, or it is the default backfill for every row that predates
 *    this column (see the migration KDoc — those rows were typed, so backfilling to [TYPED] is
 *    honest, not a guess).
 *  - [VOICE_UNCONFIRMED]: an ASR suggestion sits in UI state, not yet read and accepted by the
 *    worker. **Must never be persisted and never synced.** The repository write path refuses it
 *    — that refusal is [PR 3][scratchpad/asr-field-audit-memo.md], not this PR. This PR only adds
 *    the enum value and the column; nothing in the app can produce [VOICE_UNCONFIRMED] yet, since
 *    no voice capture UI exists on this branch.
 *  - [VOICE_CONFIRMED]: the worker read an ASR suggestion and tapped confirm, unedited.
 *  - [VOICE_EDITED]: voice-seeded, then hand-corrected before confirming. Kept distinct from
 *    [VOICE_CONFIRMED] because it is the ASR-quality signal (edit distance) a later production
 *    accuracy gate needs, measured from real use rather than only a lab test set.
 *
 *  Storage is one nullable column per voice-enabled field on the owning entity, not a separate
 *  provenance table and not a column on every field — see the memo's B.2 for why (REQ-TRS-05's
 *  "one dropdown per snapshot, not eight pickers" precedent). The first slice is exactly one
 *  column, on [Consultation.impactOnDailyActivitiesProvenance], chosen because
 *  [KernelPayload]'s own KDoc excludes [Consultation.impactOnDailyActivities] from every model
 *  path — a bug in this column cannot reach the classifier. */
enum class FieldProvenance { TYPED, VOICE_UNCONFIRMED, VOICE_CONFIRMED, VOICE_EDITED }
