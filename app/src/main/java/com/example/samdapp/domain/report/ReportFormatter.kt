package com.example.samdapp.domain.report

import com.example.samdapp.domain.model.AbhaProfile
import com.example.samdapp.domain.model.AilmentEntry
import com.example.samdapp.domain.model.Attachment
import com.example.samdapp.domain.model.AttachmentType
import com.example.samdapp.domain.model.Doctor
import com.example.samdapp.domain.model.KernelDecision
import com.example.samdapp.domain.model.KernelReportOutput
import com.example.samdapp.domain.model.MeasurementType
import com.example.samdapp.domain.model.MedicationLine
import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.model.Prescription
import com.example.samdapp.domain.model.Visibility
import com.example.samdapp.domain.model.VitalsSnapshot
import com.example.samdapp.domain.model.maskAbhaId
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import javax.inject.Inject

/**
 * Pure assembly of a [ClinicalReport] from raw Phase 0 entities (REQ-RPT-01). No Android deps, no
 * I/O — fetching is the caller's job ([com.example.samdapp.domain.usecase.AssembleReportUseCase]).
 * Every rendered field binds to a real entity field; there is no placeholder/lorem path here.
 *
 * Privacy-flag propagation (the expensive-to-redo bit): PRIVATE ailments are redacted only for
 * [ReportAudience.WORKER]. The report is always built from the full ailment list; redaction is a
 * per-audience transform applied here, so a private entry's text is absent from a WORKER report's
 * data model, not merely hidden at render — same guarantee as `CompounderViewModel.toListItem`.
 */
class ReportFormatter @Inject constructor() {

    companion object {
        /** REQ-REF-01: the referral button is always visible but only enabled when a non-measurable
         *  ailment's severity is at or above this, or the doctor REJECTed the kernel differential. */
        const val REFERRAL_SEVERITY_THRESHOLD = 8

        const val CONSENT_STATEMENT =
            "Patient has given explicit consent to create, link, and share digital health records " +
                "asynchronously under ABDM guidelines."
        const val DISCLAIMER = "AI-Assisted, Physician-Verified"
        private const val DEFAULT_PHC_NAME = "Primary Health Centre"

        /** Ambiguous Latin dosing abbreviations banned from stored/displayed text (NMC/EU) —
         *  REQ-RX-02. The formatter refuses to emit them so a bad prescription can't reach a PDF. */
        private val BANNED_FREQUENCY_TOKENS = setOf("OD", "BD", "TDS", "QID", "QDS", "SOS", "HS", "BID", "TID")
    }

    fun format(
        audience: ReportAudience,
        patient: Patient,
        abhaProfile: AbhaProfile?,
        consultationChiefComplaint: String,
        ailments: List<AilmentEntry>,
        vitals: VitalsSnapshot?,
        consultationAttachments: List<Attachment>,
        consultationRecordNo: String,
        visitDateTime: Instant,
        kernelOutput: KernelReportOutput?,
        prescription: Prescription?,
        prescribingDoctor: Doctor?,
    ): ClinicalReport {
        val isMinor = (patient.age ?: patient.dateOfBirth?.let { ageFrom(it) })?.let { it < 18 } ?: false

        val header = ReportHeader(
            phcName = patient.primaryCareClinicName?.takeIf { it.isNotBlank() } ?: DEFAULT_PHC_NAME,
            consultationRecordNo = consultationRecordNo,
            patientUid = patient.id,
            visitDateTime = visitDateTime,
        )

        val patientBlock = ReportPatientBlock(
            fullName = patient.fullName,
            guardianName = patient.guardianOrSpouseName?.takeIf { isMinor && it.isNotBlank() },
            guardianRelation = patient.guardianRelation?.takeIf { isMinor && it.isNotBlank() },
            address = joinAddress(patient),
            mobileNumber = patient.mobileNumber,
            category = patient.category,
            ageSex = "${ageSexAge(patient)} / ${patient.biologicalSex}",
            abhaNumberFormatted = patient.abhaNumber?.takeIf { it.isNotBlank() }?.let(::maskAbhaId),
            abhaAddress = abhaProfile?.abhaAddress,
            abhaVerified = abhaProfile?.kycVerified == true,
        )

        val (measurable, nonMeasurable) = ailments
            .filter { it.deletedAt == null }
            .partition { it.measurementType == MeasurementType.MEASURABLE }
        val ailmentLines = (measurable + nonMeasurable).map { entry -> toReportLine(entry, audience) }

        val prescriptionLines = prescription?.medications?.mapIndexed { i, line ->
            ReportMedicationLine(index = i + 1, text = formatMedicationLine(line))
        }.orEmpty()

        val signature = prescription?.let {
            ReportSignatureBlock(
                doctorName = prescribingDoctor?.name ?: "Physician",
                registrationNumber = prescribingDoctor?.registrationNumber,
                specialty = prescribingDoctor?.specialty,
                facilityName = prescribingDoctor?.facilityName,
            )
        }

        val attachmentLines = buildAttachmentLines(consultationAttachments)

        val activeAilments = ailments.filter { it.deletedAt == null }
        val maxAilmentSeverity = activeAilments.mapNotNull { it.severity }.maxOrNull()
        val doctorRejectedKernel = prescription?.kernelDecision == KernelDecision.REJECT
        val severityTriggered = maxAilmentSeverity != null && maxAilmentSeverity >= REFERRAL_SEVERITY_THRESHOLD
        val suggestsReferral = severityTriggered || doctorRejectedKernel
        val referralReasonSuggestion = when {
            doctorRejectedKernel ->
                "Doctor did not concur with the AI-suggested assessment; referring for specialist evaluation." +
                    (prescription?.diagnosis?.let { " Working diagnosis: $it." } ?: "")
            severityTriggered ->
                "High-severity ailment reported (severity $maxAilmentSeverity/10)." +
                    (prescription?.diagnosis?.let { " Diagnosis: $it." } ?: " Chief complaint: $consultationChiefComplaint.")
            prescription?.diagnosis != null -> "Referred following diagnosis: ${prescription.diagnosis}."
            else -> "Referred following chief complaint: $consultationChiefComplaint."
        }

        return ClinicalReport(
            audience = audience,
            header = header,
            patient = patientBlock,
            chiefComplaintVerbatim = consultationChiefComplaint,
            ailments = ailmentLines,
            vitals = vitals?.let(::toVitalLines).orEmpty(),
            kernelOutput = kernelOutput,
            attachments = attachmentLines,
            diagnosis = prescription?.diagnosis,
            kernelDecision = prescription?.kernelDecision,
            prescription = prescriptionLines,
            signature = signature,
            consentStatement = CONSENT_STATEMENT,
            disclaimer = DISCLAIMER,
            isFinal = prescription != null,
            suggestsReferral = suggestsReferral,
            referralReasonSuggestion = referralReasonSuggestion,
        )
    }

    private fun toReportLine(entry: AilmentEntry, audience: ReportAudience): ReportAilmentLine {
        val redact = entry.visibility == Visibility.PRIVATE && audience == ReportAudience.WORKER
        if (redact) {
            return ReportAilmentLine(entry.measurementType, description = null, detail = null, isRedacted = true)
        }
        val detail = when (entry.measurementType) {
            MeasurementType.MEASURABLE ->
                entry.measuredValue?.let { v -> "$v${entry.measuredUnit?.let { " $it" } ?: ""}" }
            MeasurementType.NON_MEASURABLE -> buildList {
                entry.severity?.let { add("severity $it/10") }
                entry.duration?.takeIf { it.isNotBlank() }?.let(::add)
                entry.onset?.takeIf { it.isNotBlank() }?.let { add("onset $it") }
                entry.qualifiers?.takeIf { it.isNotBlank() }?.let(::add)
            }.joinToString(" · ").ifBlank { null }
        }
        return ReportAilmentLine(entry.measurementType, entry.description, detail, isRedacted = false)
    }

    private fun toVitalLines(v: VitalsSnapshot): List<ReportVitalLine> = buildList {
        v.pulseBpm?.let { add(ReportVitalLine("Pulse", "$it bpm")) }
        if (v.bpSystolic != null && v.bpDiastolic != null) add(ReportVitalLine("Blood pressure", "${v.bpSystolic}/${v.bpDiastolic} mmHg"))
        v.spo2Percent?.let { add(ReportVitalLine("SpO₂", "$it %")) }
        v.temperatureCelsius?.let { add(ReportVitalLine("Temperature", "$it °C")) }
        v.respiratoryRate?.let { add(ReportVitalLine("Respiratory rate", "$it /min")) }
        v.weightKg?.let { add(ReportVitalLine("Weight", "$it kg")) }
        v.heightCm?.let { add(ReportVitalLine("Height", "$it cm")) }
        v.bmi?.let { add(ReportVitalLine("BMI", "$it kg/m²")) }
        v.bloodGlucoseMgDl?.let { add(ReportVitalLine("Blood glucose", "$it mg/dL")) }
        v.painScore?.let { add(ReportVitalLine("Pain score", "$it/10")) }
        v.urinalysisResult?.takeIf { it.isNotBlank() }?.let { add(ReportVitalLine("Urinalysis", it)) }
    }

    /**
     * `[Generic] ([Brand]) - [Strength] | [Route] | [Full-Text Frequency] | [Duration] | [Qty]`.
     * Throws if the stored frequency is one of the banned Latin abbreviations — REQ-RX-02 is a hard
     * rule, so the report is the last line of defence, not just the entry form.
     */
    fun formatMedicationLine(line: MedicationLine): String {
        require(line.frequency.trim().uppercase() !in BANNED_FREQUENCY_TOKENS) {
            "Frequency '${line.frequency}' is an ambiguous Latin abbreviation; write it out in full (NMC/EU, REQ-RX-02)"
        }
        val brand = line.brandName?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        return "${line.genericName}$brand - ${line.strength} | ${line.route} | ${line.frequency} | " +
            "${line.duration} | ${line.quantity}"
    }

    private fun buildAttachmentLines(attachments: List<Attachment>): List<ReportAttachmentEntry> {
        val counters = mutableMapOf<AttachmentType, Int>()
        return attachments.map { attachment ->
            val n = (counters[attachment.type] ?: 0) + 1
            counters[attachment.type] = n
            val label = when (attachment.type) {
                AttachmentType.IMAGE -> "Photo $n"
                AttachmentType.AFFECTED_AREA_PHOTO -> "Affected area photo $n"
                AttachmentType.VIDEO -> "Video $n"
                AttachmentType.AUDIO -> "Audio $n"
            }
            ReportAttachmentEntry(type = attachment.type, uri = attachment.uri, label = label)
        }
    }

    private fun joinAddress(p: Patient): String? =
        listOfNotNull(p.village, p.block, p.district, p.state, p.pincode)
            .filter { it.isNotBlank() }
            .joinToString(", ")
            .ifBlank { null }

    private fun ageSexAge(p: Patient): String =
        (p.age ?: p.dateOfBirth?.let { ageFrom(it) })?.let { "${it}y" } ?: "—"

    private fun ageFrom(dob: LocalDate): Int = Period.between(dob, LocalDate.now(ZoneId.systemDefault())).years
}
