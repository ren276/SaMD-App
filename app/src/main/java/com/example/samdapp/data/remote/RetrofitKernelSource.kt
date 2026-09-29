package com.example.samdapp.data.remote

import com.example.samdapp.data.remote.api.KernelApiService
import com.example.samdapp.data.remote.dto.KernelAssessmentRequestDto
import com.example.samdapp.data.remote.dto.KernelAssessmentResponseDto
import com.example.samdapp.data.remote.dto.ProblemDetailDto
import com.example.samdapp.domain.kernel.KernelApiResult
import com.example.samdapp.domain.kernel.KernelAssessmentResult
import com.example.samdapp.domain.kernel.RemoteKernelSource
import com.example.samdapp.domain.model.KernelPayload
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import java.io.IOException
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
 * Returns a [KernelApiResult] instead of throwing. Until this change the KDoc here said "any
 * IOException / HttpException propagates to the caller, this class never swallows exceptions",
 * which was true and was the problem: the caller's one `catch (e: Exception)` then had only
 * `HttpException.message` to work with, which is the status line, and the RFC 9457 problem
 * document in the body carrying the `SAMD-*` code was discarded unread. `ProblemDetailDto` was
 * already parsed on two other paths (`RetrofitAuthService`, `RetrofitAbhaSource`); this is the
 * third instance of the same block, deliberately identical so a reader sees one thing three
 * times rather than three things.
 *
 * `CancellationException` is still rethrown: that is the caller's coroutine being cancelled, not
 * a server outcome, and it must not become a [KernelApiResult].
 */
class RetrofitKernelSource @Inject constructor(
    private val kernelApiService: KernelApiService,
) : RemoteKernelSource {

    private val gson = Gson()

    override suspend fun assess(
        payload: KernelPayload,
        patientAge: Int,
        patientSex: String,
    ): KernelApiResult<KernelAssessmentResult> {
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

        val response = try {
            kernelApiService.assess(request)
        } catch (e: CancellationException) {
            // Before the RuntimeException branch below, which it otherwise matches
            // (java.util.concurrent.CancellationException is a RuntimeException). Same ordering
            // and same reason as RetrofitAbhaSource.
            throw e
        } catch (e: IOException) {
            // The bytes did not get there. The cause travels to classifyKernelFailure, which
            // branches on its TYPE to tell OFFLINE from TIMEOUT from SECURE_CONNECTION_FAILED.
            // Never rendered: an IOException message can quote the host and port.
            return KernelApiResult.Unreachable(e)
        } catch (e: RuntimeException) {
            // The HTTP exchange succeeded and Gson then failed to build the DTO: a malformed or
            // contract-drifted body. Not Unreachable, for the reason AbhaApiResult.ProtocolViolation
            // records: a server that answered unintelligibly is not a server that was absent.
            // The exception message is deliberately not interpolated, only its class name.
            return KernelApiResult.ProtocolViolation(
                "Malformed response body from the backend (${e.javaClass.simpleName})",
            )
        }

        if (!response.isSuccessful) {
            // The house block, third instance. Identical to RetrofitAuthService.call and
            // RetrofitAbhaSource.call: read the error body once, try to read it as a problem
            // document, and accept null (absent body, or a body that is not a problem document)
            // rather than throwing on it.
            val problem = response.errorBody()?.charStream()?.use { reader ->
                runCatching { gson.fromJson(reader, ProblemDetailDto::class.java) }.getOrNull()
            }
            return KernelApiResult.Failure(
                code = problem?.code,
                httpStatus = response.code(),
                message = problem?.detail ?: "Request failed (HTTP ${response.code()}).",
            )
        }

        val envelope = response.body()
            ?: return KernelApiResult.ProtocolViolation("Empty response body from the backend.")
        val assessment = envelope.data

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
        val differentials = assessment.differentialDiagnosis.orEmpty()
        val topDiff = differentials.firstOrNull()

        // No differential means no assessment: predictedCondition stays null and the use case
        // routes the case to InferenceSource.UNAVAILABLE. Substituting a placeholder condition
        // and confidence here (as this did until the empty-200 fabrication fix) put invented
        // clinical content in front of a clinician stamped REAL_INFERENCE, indistinguishable
        // from a real model output because the call itself had succeeded.
        return KernelApiResult.Success(
            KernelAssessmentResult(
                predictedCondition = topDiff?.conditionTier,
                confidenceScore = topDiff?.probability?.coerceIn(0.0, 1.0) ?: 0.0,
                triageUrgency = assessment.triageUrgency,
                safetyScreenPassed = assessment.safetyScreenPassed,
                evidenceFor = topDiff?.evidenceFor.orEmpty(),
                evidenceAgainst = topDiff?.evidenceAgainst.orEmpty(),
                differentials = differentials.drop(1).map { it.conditionTier },
                recommendedInvestigations = assessment.recommendedInvestigations,
                modelVersion = assessment.modelMetadata?.modelVersion,
            ),
        )
    }
}
