package com.example.samdapp.data.remote

import com.example.samdapp.data.remote.api.KernelApiService
import com.example.samdapp.data.remote.dto.KernelAssessmentRequestDto
import com.example.samdapp.domain.kernel.KernelAssessmentResult
import com.example.samdapp.domain.kernel.RemoteKernelSource
import com.example.samdapp.domain.model.KernelPayload
import javax.inject.Inject

/**
 * Retrofit-backed implementation of [RemoteKernelSource].
 *
 * Talks to the FastAPI kernel at the LAN IP configured in [com.example.samdapp.di.NetworkModule]
 * (physical-device testing over Wi-Fi, not the emulator loopback).
 *
 * Translates [KernelPayload] → [KernelAssessmentRequestDto] → API call →
 * [KernelAssessmentResponseDto] → [KernelAssessmentResult].
 *
 * Any IOException / HttpException propagates to the caller ([GenerateKernelReportUseCase])
 * which catches it and falls back to mock inference. This class itself never swallows exceptions.
 */
class RetrofitKernelSource @Inject constructor(
    private val kernelApiService: KernelApiService,
) : RemoteKernelSource {

    override suspend fun assess(
        payload: KernelPayload,
        patientAge: Int,
        patientSex: String,
    ): KernelAssessmentResult {
        val vitals = payload.vitals

        // Compute BMI from vitals if height/weight are present; default 22.0 (normal) when absent.
        val bmi = if (vitals.weightKg != null && vitals.heightCm != null && vitals.heightCm > 0) {
            val hm = vitals.heightCm / 100.0
            (vitals.weightKg / (hm * hm) * 10.0).let { kotlin.math.round(it) / 10.0 }
        } else {
            22.0
        }

        val request = KernelAssessmentRequestDto(
            caseToken = payload.caseToken,
            age = patientAge,
            // Same normalization as RetrofitEvaluateSource — the backend checks `sex.upper() == "M"`
            // exactly, and Patient.biologicalSex is a full word ("Male"/"Female"), not a letter.
            sex = patientSex.take(1).uppercase(),
            systolicBp = vitals.bpSystolic?.toDouble() ?: 120.0,
            diastolicBp = vitals.bpDiastolic?.toDouble() ?: 80.0,
            bmi = bmi,
            heartRate = vitals.pulseBpm?.toDouble() ?: 72.0,
            randomGlucose = vitals.bloodGlucoseMgDl?.toDouble() ?: 100.0,
            spo2 = vitals.spo2Percent?.toDouble() ?: 98.0,
        )

        val response = kernelApiService.assess(request).data

        // Map DTO → domain result. Field bindings (as specified):
        // top differential's condition_tier  → predictedCondition
        // top differential's probability     → confidenceScore
        // top differential's evidence_for    → evidenceFor
        // top differential's evidence_against→ evidenceAgainst
        // triage_urgency                     → included in reasoningSummary by the use case
        // .orEmpty() collapses an absent/null differential_diagnosis key and a present-but-empty
        // list into the same "no differential" case, so both reach GenerateKernelReportUseCase's
        // empty-differential branch identically rather than the absent-key case NPE-ing into the
        // generic catch (which would look identical to an unreachable kernel in the audit trail).
        val differentials = response.differentialDiagnosis.orEmpty()
        val topDiff = differentials.firstOrNull()

        // No differential means no assessment: predictedCondition stays null and the use case
        // routes the case to InferenceSource.UNAVAILABLE. Substituting a placeholder condition
        // and confidence here (as this did until the empty-200 fabrication fix) put invented
        // clinical content in front of a clinician stamped REAL_INFERENCE, indistinguishable
        // from a real model output because the call itself had succeeded.
        return KernelAssessmentResult(
            predictedCondition = topDiff?.conditionTier,
            confidenceScore = topDiff?.probability?.coerceIn(0.0, 1.0) ?: 0.0,
            triageUrgency = response.triageUrgency,
            safetyScreenPassed = response.safetyScreenPassed,
            evidenceFor = topDiff?.evidenceFor.orEmpty(),
            evidenceAgainst = topDiff?.evidenceAgainst.orEmpty(),
            differentials = differentials.drop(1).map { it.conditionTier },
            recommendedInvestigations = response.recommendedInvestigations,
            modelVersion = response.modelMetadata?.modelVersion,
        )
    }
}
