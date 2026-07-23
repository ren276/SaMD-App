package com.example.samdapp.domain.model

import java.time.Instant

/**
 * Mirrors `refine_diagnosis.py`'s `DiagnosisFeedback` Pydantic schema in SaMDClassifier — the
 * physician's confirm/correct/discard decision on the AI's top diagnostic candidate.
 *
 * Per that schema's own docstring (schema-only there today, no capture/reimport pipeline wired
 * up on the backend yet): only [PhysicianDecision.AGREE] and [PhysicianDecision.MODIFY] are ever
 * eligible for a future training-dataset re-import — AGREE confirms the AI's candidate as ground
 * truth, MODIFY substitutes [physicianFinalDiagnosis] as the corrected ground truth.
 * [PhysicianDecision.REJECT] is never reimported — there is no ground-truth diagnosis to trust.
 *
 * Stored locally only (no live backend capture endpoint exists yet) — this is the demo-visible
 * capture point for that future pipeline.
 */
enum class PhysicianDecision { AGREE, MODIFY, REJECT }

data class DiagnosisFeedback(
    val id: String,
    val caseRecordId: String,
    val icdCandidate: String,
    val physicianDecision: PhysicianDecision,
    /**
     * The corrected ICD code when [physicianDecision] is [PhysicianDecision.MODIFY] — null
     * otherwise. MUST be one of [TRAINED_ICD_CANDIDATES] or null; this field is the exact shape
     * `refine_diagnosis.py`'s `DiagnosisFeedback.physician_final_diagnosis` expects for a future
     * training-dataset reimport, and the symptom classifier has no class outside that list to
     * reimport into. Never a drug/brand/company name and never free text — see [clinicalNote]
     * for anything that falls outside the trained class list.
     */
    val physicianFinalDiagnosis: String?,
    /**
     * Optional free-text clinical/audit note — e.g. when the physician's real correction isn't
     * one of [TRAINED_ICD_CANDIDATES]. Captured for clinical record-keeping only; deliberately
     * NEVER copied into [physicianFinalDiagnosis] and NEVER reimported into the training dataset
     * (no dataset column or reimport contract exists for free text — expanding class support is
     * explicitly out of scope here).
     */
    val clinicalNote: String?,
    val createdAt: Instant,
)
