package com.example.samdapp.domain.doctor

import com.example.samdapp.domain.model.KernelDecision
import com.example.samdapp.domain.model.MedicationLine

/**
 * What arrives back from the doctor's own review — built and run via a **separate communication
 * channel**, out of scope for this PHC-worker app (no doctor-facing prescription-entry UI lives
 * here). This is just the shape of the payload that channel hands us.
 */
data class IncomingPrescription(
    val doctorId: String,
    val diagnosis: String,
    val medications: List<MedicationLine>,
    val kernelDecision: KernelDecision,
)

/**
 * The receiving end of the doctor's out-of-app review (REQ-RX-01/03). A real implementation would
 * poll a backend API, receive a push/webhook, or read a message queue the doctor's app writes to —
 * none of that transport exists yet, so [MockDoctorPrescriptionInbox][com.example.samdapp.data.doctor.MockDoctorPrescriptionInbox]
 * simulates "the doctor has reviewed and responded" for the demo. Same pattern as
 * [com.example.samdapp.domain.vitalssource.VitalsSource]/[com.example.samdapp.domain.transcription.TranscriptionService] —
 * a named mock boundary the caller depends on only through this interface, so swapping in the real
 * transport later touches one binding, not every call site.
 *
 * Returns `null` (not a failure) when the doctor simply hasn't responded yet — that's the normal,
 * expected async state, not an error.
 */
interface DoctorPrescriptionInbox {
    suspend fun fetchPrescription(caseRecordId: String): Result<IncomingPrescription?>
}
