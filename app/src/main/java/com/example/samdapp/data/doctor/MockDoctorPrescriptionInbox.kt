package com.example.samdapp.data.doctor

import com.example.samdapp.domain.doctor.DoctorPrescriptionInbox
import com.example.samdapp.domain.doctor.IncomingPrescription
import com.example.samdapp.domain.model.KernelDecision
import com.example.samdapp.domain.model.MedicationLine
import com.example.samdapp.domain.repository.CaseRecordRepository
import com.example.samdapp.domain.repository.KernelReportRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import kotlin.random.Random

/**
 * Stands in for the real doctor channel (a separate app/API/webhook — out of scope here) until
 * that transport exists. Simulates "the doctor has read the case's kernel assessment and
 * responded" rather than returning static text, so the demo shows believable variety: weighted
 * toward AGREE (doctors usually agree with a reasonable AI suggestion), with MODIFY/REJECT for
 * texture. This is the only place that fabricates a doctor's decision — swap this one class for a
 * real API/webhook client later; nothing else in the app depends on it being mocked.
 */
class MockDoctorPrescriptionInbox @Inject constructor(
    private val caseRecordRepository: CaseRecordRepository,
    private val kernelReportRepository: KernelReportRepository,
) : DoctorPrescriptionInbox {

    companion object {
        private const val MOCK_DOCTOR_ID = "doc-gen-001"
    }

    override suspend fun fetchPrescription(caseRecordId: String): Result<IncomingPrescription?> = runCatching {
        val caseRecord = caseRecordRepository.observeCaseRecord(caseRecordId).first() ?: return@runCatching null
        if (caseRecord.assignedDoctorId == null) return@runCatching null

        delay(Random.nextLong(600L, 1400L))

        val kernelOutput = kernelReportRepository.getForCase(caseRecordId)
        val roll = Random.nextDouble()
        val decision = when {
            kernelOutput == null -> KernelDecision.MODIFY
            roll < 0.65 -> KernelDecision.AGREE
            roll < 0.9 -> KernelDecision.MODIFY
            else -> KernelDecision.REJECT
        }
        val diagnosis = when (decision) {
            KernelDecision.AGREE -> kernelOutput?.predictedCondition ?: "Clinical assessment pending further evaluation"
            KernelDecision.MODIFY -> kernelOutput?.differentials?.firstOrNull() ?: kernelOutput?.predictedCondition
                ?: "Clinical assessment pending further evaluation"
            KernelDecision.REJECT -> "Clinical assessment pending further evaluation (AI suggestion not clinically supported)"
        }

        IncomingPrescription(
            doctorId = caseRecord.assignedDoctorId ?: MOCK_DOCTOR_ID,
            diagnosis = diagnosis,
            medications = listOf(
                MedicationLine(
                    genericName = "Paracetamol",
                    brandName = null,
                    strength = "500 mg",
                    dosage = "1 tablet",
                    frequency = "three times daily",
                    route = "oral",
                    duration = "5 days",
                    quantity = "15 tablets",
                    foodRelation = "after food",
                    instructions = "Discontinue if symptoms resolve earlier",
                ),
            ),
            kernelDecision = decision,
        )
    }
}
