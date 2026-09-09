package com.example.samdapp.domain.slm

import com.example.samdapp.domain.model.KernelDecision

/**
 * The only shape a physician-approved record may take on its way to the on-device SLM readback
 * (`scratchpad/slm-guardrail-service-contract-memo.md` §4.1/§4.2). Structurally decoupled from
 * patient identity in exactly the way [com.example.samdapp.domain.model.KernelPayload] is for the
 * clinical kernel (risk H-10): no field here is of type [com.example.samdapp.domain.model.Patient]
 * or [com.example.samdapp.domain.report.ReportPatientBlock], and
 * [ApprovedRecordReader]'s signature takes a case record id rather than a `Patient`, so identity
 * cannot reach the model boundary even by mistake.
 *
 * Deliberately excludes: `fullName`, `guardianName`, `address`, `mobileNumber`, `abhaNumber`,
 * `abhaAddress`, and every other [com.example.samdapp.domain.report.ReportPatientBlock] field.
 * Also excludes the chief complaint, the ailment lines, the vitals, the raw kernel/evaluate output
 * and every attachment — not because each is identifying, but because a readback of a decision the
 * physician already made does not need them, and the memo's §4.2 field list is a whitelist, not a
 * starting point. Age/sex is likewise absent: §4.2 admits it "only if the readback genuinely needs
 * it", and nothing in this stage establishes that need. Add a field deliberately, with a reason,
 * or not at all.
 *
 * [caseRecordId] is the correlation token, same posture as [com.example.samdapp.domain.model.KernelPayload.caseToken]:
 * an opaque id, not a display value.
 *
 * [kernelDecision] is non-null by construction. That is the whole approval guarantee: this type
 * cannot be instantiated for a case no physician has decided on, so "is this record approved" is
 * answered by the type, not by a caller remembering to check.
 *
 * [medicationLines] are already formatted by [com.example.samdapp.domain.report.ReportFormatter.formatMedicationLine]
 * (full-text frequency, no OD/BD/SOS, REQ-RX-02) and already gated by the audience the reader
 * derived — a REJECTed case read by a worker-tier viewer carries no medication lines at all.
 */
data class ApprovedRecordSnapshot(
    val caseRecordId: String,
    val kernelDecision: KernelDecision,
    val diagnosis: String?,
    val medicationLines: List<String>,
    val suggestsReferral: Boolean,
)

/**
 * Why a snapshot could not be produced. A refusal is never an empty or partially-filled snapshot:
 * harness finding F5 is that an empty input to this model produces confident fabrication, not an
 * abstention, so "nothing to read back" has to be unrepresentable as a snapshot.
 */
enum class SnapshotRefusal {
    /** No committed [KernelDecision] for the case. The record is not physician-approved and
     *  readback is not permitted at all (memo §4.1). */
    NOT_APPROVED,

    /** No such case record. Nothing to read back, and not a reason to generate. */
    CASE_UNRESOLVABLE,

    /** The case resolved but the report could not be assembled from it — a missing patient row, or
     *  a formatter refusal such as [com.example.samdapp.domain.report.ReportFormatter.formatMedicationLine]'s
     *  banned-abbreviation `require`. */
    ASSEMBLY_FAILED,
}

/** Outcome of [ApprovedRecordReader]. */
sealed interface ApprovedRecordResult {
    data class Available(val snapshot: ApprovedRecordSnapshot) : ApprovedRecordResult
    data class Refused(val reason: SnapshotRefusal) : ApprovedRecordResult
}
