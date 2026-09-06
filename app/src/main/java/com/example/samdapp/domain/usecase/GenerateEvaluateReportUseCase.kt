package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.kernel.BrandLookupSource
import com.example.samdapp.domain.kernel.EvaluateKernelSource
import com.example.samdapp.domain.model.EvaluateReportOutput
import com.example.samdapp.domain.model.KernelPayload
import com.example.samdapp.domain.repository.EvaluateReportRepository
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger
import javax.inject.Inject

/**
 * Generates an [EvaluateReportOutput] via the real `/api/v1/evaluate` NLEM-treatment kernel.
 *
 * Unlike [GenerateKernelReportUseCase], there is no mock fallback here — this endpoint's value
 * IS the real inference (drug recommendation, NLEM citation, vitals triage grades), so a failure
 * surfaces as [Result.failure] rather than fabricating treatment data. The report screen simply
 * omits this section when it's absent, same as the preliminary-report path omits [kernelOutput].
 */
class GenerateEvaluateReportUseCase @Inject constructor(
    private val evaluateReportRepository: EvaluateReportRepository,
    private val evaluateKernelSource: EvaluateKernelSource,
    private val brandLookupSource: BrandLookupSource,
) {
    companion object {
        private val logger = Logger.getLogger("EvaluateUseCase")
        const val EMPTY_SYMPTOM_INPUT = "EMPTY_SYMPTOM_INPUT"
    }

    /**
     * True when neither field has a single letter or digit — the exact logical complement of the
     * `symptomString` build at RetrofitEvaluateSource:45-48, widened past strict blankness to also
     * catch an all-punctuation input (e.g. "???"). See docs/quality/risk-management-file.md H-20:
     * an empty symptom_string measured a confident E66 (Obesity) differential at 74.95% — the
     * model's training prior, not an assessment of this patient. The all-punctuation case is an
     * unmeasured precaution, not the measured hazard.
     */
    private fun KernelPayload.hasNoSymptomText(): Boolean =
        chiefComplaint.none(Char::isLetterOrDigit) && transcription.orEmpty().none(Char::isLetterOrDigit)

    suspend operator fun invoke(
        caseRecordId: String,
        payload: KernelPayload,
        patientAge: Int? = null,
        patientSex: String? = null,
    ): Result<EvaluateReportOutput> {
        val inferenceStartedAt = Instant.now()

        if (payload.hasNoSymptomText()) {
            logger.warning("Empty symptom input for case $caseRecordId — evaluate not called.")
            evaluateReportRepository.saveFailure(caseRecordId, EMPTY_SYMPTOM_INPUT)
            return Result.failure(IllegalStateException(EMPTY_SYMPTOM_INPUT))
        }

        return try {
            val result = evaluateKernelSource.evaluate(
                payload = payload,
                patientAge = patientAge ?: 30,
                patientSex = patientSex ?: "U",
            )
            // Best-effort — never throws, never blocks the report on a slow/unreachable Gemini call.
            val topIndianBrand = result.nlemTreatment.recommendedDrug
                ?.let { brandLookupSource.lookupTopIndianBrand(it) }
            val output = EvaluateReportOutput(
                id = UUID.randomUUID().toString(),
                caseRecordId = caseRecordId,
                diagnosticSummary = result.diagnosticSummary,
                nlemTreatment = result.nlemTreatment,
                brandMapping = result.brandMapping,
                safetyAndTriage = result.safetyAndTriage,
                topIndianBrand = topIndianBrand,
                inferenceStartedAt = inferenceStartedAt,
                inferenceEndedAt = Instant.now(),
            )
            evaluateReportRepository.save(output).map { output }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warning("Evaluate API unavailable — recording an honest failure marker. Reason: ${e.message}")
            // H-14: persists the failure so it is readable back, distinguishable from "hasn't run
            // yet" — see EvaluateReportRepository.saveFailure's KDoc. Best-effort: if even this
            // write fails, the caller still gets the original Result.failure(e) below.
            evaluateReportRepository.saveFailure(caseRecordId, e::class.simpleName ?: "UNKNOWN_ERROR")
            Result.failure(e)
        }
    }
}
