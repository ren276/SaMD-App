package com.example.samdapp.domain.model

import java.time.Instant

/**
 * One line of a prescription, structured per the prescription-writing "4 pillars"
 * (superscription/inscription/subscription/signatura, WJPPS reference).
 *
 * HARD RULE (NMC / EU, not style): [frequency] and every dosing instruction must be written out in
 * full — never the ambiguous Latin abbreviations OD/BD/TDS/QID/SOS/HS anywhere in stored or
 * displayed text. Write "twice daily", not "BD". Enforced at the doctor entry layer (Phase 5).
 *
 * [genericName] is required (SI/generic-first convention); [brandName] is optional.
 */
data class MedicationLine(
    val genericName: String,
    val brandName: String?,
    val strength: String,
    val dosage: String,
    val frequency: String,
    val route: String,
    val duration: String,
    val quantity: String,
    val foodRelation: String?,
    val instructions: String?,
)

/**
 * The doctor's decision on the kernel's predicted differential (REQ-RX-03) — the doctor's own
 * review UI is out of scope for this app (built via a separate communication channel); this
 * enum is just the shape of what that channel reports back.
 */
enum class KernelDecision { AGREE, MODIFY, REJECT }

/**
 * The doctor's prescription block for a case (Phase 5). One prescription per case record;
 * [medications] persists to the child `medication_lines` table, mirroring the
 * Consultation→Attachment relationship. [kernelDecision] is null on prescriptions predating this
 * field (additive migration).
 */
data class Prescription(
    val id: String,
    val patientId: String,
    val encounterId: String,
    val caseRecordId: String,
    val doctorId: String,
    val diagnosis: String,
    val medications: List<MedicationLine>,
    val kernelDecision: KernelDecision?,
    val createdAt: Instant,
)
