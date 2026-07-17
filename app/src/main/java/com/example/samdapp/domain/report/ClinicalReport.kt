package com.example.samdapp.domain.report

import com.example.samdapp.domain.model.AttachmentType
import com.example.samdapp.domain.model.KernelDecision
import com.example.samdapp.domain.model.KernelReportOutput
import com.example.samdapp.domain.model.MeasurementType
import java.time.Instant

/**
 * The single, progressively-assembled clinical report object (REQ-RPT-01/02). One report, sections
 * added as the case moves through the pipeline — NOT two separate documents:
 *  - Phase 3 (preliminary): [kernelOutput] null, [prescription] empty, [signature] null, [isFinal] false.
 *  - Phase 4 appends [kernelOutput].
 *  - Phase 5 appends [prescription] + [diagnosis] + [signature] and flips [isFinal] true.
 *
 * Both the on-screen Compose preview and the exported PDF render from THIS object via the same
 * `ReportCanvasRenderer` — one layout, two surfaces.
 */
enum class ReportAudience {
    /** On-device, health-worker present. PRIVATE ailments are redacted (REQ-AIL-02). */
    WORKER,

    /** The physician's clinical document. Shows everything — the doctor is the clinician the
     *  private entries were always meant for; the worker-facing redaction never applied to them. */
    PHYSICIAN,
}

data class ReportHeader(
    val phcName: String,
    /** Consultation Record No — the case record id, our stable end-to-end correlation key. */
    val consultationRecordNo: String,
    /** Patient UID — the barcode payload and the human-readable id printed beneath it. */
    val patientUid: String,
    val visitDateTime: Instant,
)

data class ReportPatientBlock(
    val fullName: String,
    val guardianName: String?,
    val guardianRelation: String?,
    val address: String?,
    val mobileNumber: String?,
    val category: String?,
    val ageSex: String,
    /** Display-formatted `XX-XXXX-XXXX-XXXX`, or null if the patient has no ABHA link. */
    val abhaNumberFormatted: String?,
    val abhaAddress: String?,
    val abhaVerified: Boolean,
)

data class ReportAilmentLine(
    val measurementType: MeasurementType,
    /** Null when [isRedacted] — a redacted private line carries no clinical text at all. */
    val description: String?,
    val detail: String?,
    val isRedacted: Boolean,
)

data class ReportVitalLine(val label: String, val value: String)

/**
 * A consultation attachment carried through to the report unmodified — same posture as
 * [com.example.samdapp.domain.model.KernelPayload], which already passes attachments through to
 * the kernel untouched (REQ-HAN-06 KDoc). [uri] is resolved to an actual image by the renderer's
 * caller (a Context is needed to decode it; the renderer itself stays plain-graphics).
 */
data class ReportAttachmentEntry(val type: AttachmentType, val uri: String, val label: String)

data class ReportMedicationLine(
    val index: Int,
    /** Pre-formatted, full-text (no OD/BD/SOS) — see [ReportFormatter.formatMedicationLine]. */
    val text: String,
)

data class ReportSignatureBlock(val doctorName: String, val registrationNumber: String?)

data class ClinicalReport(
    val audience: ReportAudience,
    val header: ReportHeader,
    val patient: ReportPatientBlock,
    val chiefComplaintVerbatim: String,
    /** Measurable lines first, then non-measurable — the ordering REQ-RPT-01 mandates. */
    val ailments: List<ReportAilmentLine>,
    val vitals: List<ReportVitalLine>,
    val kernelOutput: KernelReportOutput?,
    val attachments: List<ReportAttachmentEntry>,
    val diagnosis: String?,
    /** Doctor's Agree/Modify/Reject on the kernel differential — reported back via the out-of-app
     *  doctor channel (REQ-RX-03), null on the preliminary report. */
    val kernelDecision: KernelDecision?,
    val prescription: List<ReportMedicationLine>,
    val signature: ReportSignatureBlock?,
    val consentStatement: String,
    val disclaimer: String,
    val isFinal: Boolean,
    /** Referral button (REQ-REF-01) is always visible on the report screen but only ENABLED when
     *  this is true — a high-severity ailment or a doctor REJECT of the kernel differential. See
     *  [ReportFormatter.REFERRAL_SEVERITY_THRESHOLD]. Decision recorded in PROGRESS.md per the
     *  brief's "pick one, don't leave it ambiguous" instruction. */
    val suggestsReferral: Boolean,
    /** Auto-fill seed for the referral confirm sheet's editable reason field — never null so the
     *  sheet is never blank, even when [suggestsReferral] is false and the worker refers anyway. */
    val referralReasonSuggestion: String,
)
