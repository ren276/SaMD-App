package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.ReferralRequest
import com.example.samdapp.domain.model.ReferralStatus
import com.example.samdapp.domain.model.UrgencyLevel
import com.example.samdapp.domain.repository.ReferralRepository
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * Single PHC-side action (REQ-REF-01) — creates a [ReferralRequest] and nothing else. There is
 * deliberately no receiving-side system: [ReferralStatus] never moves past [ReferralStatus.QUEUED]
 * in this app; the confirmation message is the entire user-visible outcome.
 */
class CreateReferralUseCase @Inject constructor(
    private val referralRepository: ReferralRepository,
) {
    suspend operator fun invoke(
        patientUid: String,
        caseRecordId: String,
        urgencyLevel: UrgencyLevel,
        reason: String,
        sendingPhcId: String,
    ): Result<ReferralRequest> {
        if (reason.isBlank()) return Result.failure(IllegalArgumentException("Referral reason is required"))
        val referral = ReferralRequest(
            id = UUID.randomUUID().toString(),
            patientUid = patientUid,
            caseRecordId = caseRecordId,
            urgencyLevel = urgencyLevel,
            reason = reason,
            sendingPhcId = sendingPhcId,
            status = ReferralStatus.QUEUED,
            timestamp = Instant.now(),
        )
        return referralRepository.createReferral(referral).map { referral }
    }
}
