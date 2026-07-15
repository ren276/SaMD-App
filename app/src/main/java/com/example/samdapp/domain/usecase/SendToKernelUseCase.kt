package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.Consultation
import com.example.samdapp.domain.model.KernelPayload
import com.example.samdapp.domain.model.VitalsReading
import kotlinx.coroutines.delay
import javax.inject.Inject
import kotlin.random.Random

/**
 * Represents the (future, real) handoff to the AI processing kernel. No network call yet —
 * the delay is what the Sending screen's progress indicator is shown against.
 *
 * Structural pseudonymization boundary: this signature has no parameter of type `Patient`, so a
 * Patient object cannot reach the kernel even by accident — [KernelPayload] is assembled here
 * from only the whitelisted [vitals]/[consultation] fields plus [caseToken].
 *
 * [caseToken] reuses [com.example.samdapp.domain.model.CaseRecord.id] rather than a separate
 * opaque token: it's already a random UUID with no embedded patient information, it's already
 * the app's own end-to-end case-correlation key (audit log, doctor assignment), and there's no
 * real network-facing kernel yet for a leaked token to matter against. When a real kernel exists
 * behind a network boundary, prefer minting a separate opaque token instead of reusing the case
 * primary key — that avoids conflating an internal DB identity with an external-facing
 * correlation id (reduces blast radius if a token leaks in transit/logs).
 */
class SendToKernelUseCase @Inject constructor() {
    suspend operator fun invoke(vitals: VitalsReading, consultation: Consultation, caseToken: String): Result<KernelPayload> {
        val payload = KernelPayload(
            caseToken = caseToken,
            vitals = vitals,
            chiefComplaint = consultation.chiefComplaint,
            durationBucket = consultation.durationBucket,
            severityScore = consultation.severityScore,
            relevantHistory = consultation.relevantHistory,
            transcription = consultation.transcription,
            attachments = consultation.attachments,
        )
        delay(Random.nextLong(1000L, 2000L))
        return Result.success(payload)
    }
}
