package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.Attachment
import com.example.samdapp.domain.model.AttachmentType
import com.example.samdapp.domain.model.Consultation
import com.example.samdapp.domain.model.VitalsReading
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * REQ-HAN-06 / risk H-10: the kernel payload boundary must never carry patient identity.
 * SendToKernelUseCase's signature has no Patient parameter at all (compile-time proof); this
 * test proves the runtime payload it assembles from a "hot" consultation (deliberately packed
 * with identity-shaped strings in fields the use case must NOT read) carries none of it.
 */
class SendToKernelUseCaseTest {

    private val identityLeakCanary = "Anita Kumari 9998887776 AADHAAR-123412341234"

    private fun consultationLacedWithIdentityInUnlistedFields() = Consultation(
        id = "c1",
        patientId = "p1", // present on the domain object; must not end up in the payload
        encounterId = "e1",
        chiefComplaint = "Fever",
        onset = identityLeakCanary, // NOT in the whitelist — must not leak via a careless "copy all"
        durationBucket = "few_days",
        severityScore = 6,
        aggravatingFactors = identityLeakCanary, // NOT in the whitelist
        relievingFactors = identityLeakCanary, // NOT in the whitelist
        impactOnDailyActivities = identityLeakCanary, // NOT in the whitelist
        impactOnDailyActivitiesProvenance = null,
        relevantHistory = "No known allergies",
        transcription = "patient reports fever since two days",
        attachments = listOf(
            Attachment(id = "a1", consultationId = "c1", type = AttachmentType.IMAGE, uri = "content://x", createdAt = Instant.EPOCH),
        ),
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    @Test
    fun `payload contains only the whitelisted fields, never patientId or the unlisted consultation fields`() = runTest {
        val consultation = consultationLacedWithIdentityInUnlistedFields()
        val vitals = VitalsReading(pulseBpm = 88, bpSystolic = 120, bpDiastolic = 80, deviceId = "dev-1")

        val payload = SendToKernelUseCase()(vitals, consultation, caseToken = "case-token-123").getOrThrow()

        // Whitelisted fields carried through correctly.
        assertEquals("case-token-123", payload.caseToken)
        assertEquals(vitals, payload.vitals)
        assertEquals("Fever", payload.chiefComplaint)
        assertEquals("few_days", payload.durationBucket)
        assertEquals(6, payload.severityScore)
        assertEquals("No known allergies", payload.relevantHistory)
        assertEquals("patient reports fever since two days", payload.transcription)
        assertEquals(consultation.attachments, payload.attachments)

        // The payload's own string representation — the strongest available proxy for "nothing
        // extra leaked" — must not contain patientId or any of the identity-laced unlisted fields.
        val serialized = payload.toString()
        assertFalse("patientId leaked into payload", serialized.contains(consultation.patientId))
        assertFalse("unlisted onset field leaked", serialized.contains(identityLeakCanary))
    }

    @Test
    fun `missing vitals defaults to an all-null VitalsReading, not a crash`() = runTest {
        val consultation = consultationLacedWithIdentityInUnlistedFields()

        val payload = SendToKernelUseCase()(VitalsReading(), consultation, caseToken = "case-token-456").getOrThrow()

        assertTrue(payload.vitals == VitalsReading())
    }
}
