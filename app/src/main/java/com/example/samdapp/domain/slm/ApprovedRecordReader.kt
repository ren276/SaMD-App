package com.example.samdapp.domain.slm

import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.auth.CadreTier
import com.example.samdapp.domain.auth.UserSession
import com.example.samdapp.domain.auth.toCadreTier
import com.example.samdapp.domain.report.ReportAudience
import com.example.samdapp.domain.repository.CaseRecordRepository
import com.example.samdapp.domain.usecase.AssembleReportUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Turns one case record id into an [ApprovedRecordSnapshot], or refuses.
 * `scratchpad/slm-guardrail-service-contract-memo.md` §4.2 item 2: the reader resolves the case,
 * requires a committed [com.example.samdapp.domain.model.KernelDecision], and returns a typed
 * [ApprovedRecordResult.Refused] otherwise. It never returns an empty snapshot (harness F5).
 *
 * **The signature takes a case record id, never a [com.example.samdapp.domain.model.Patient].**
 * Same construction guarantee as [com.example.samdapp.domain.usecase.SendToKernelUseCase] for
 * risk H-10: identity cannot cross this boundary because there is no parameter it could cross on.
 *
 * **The audience is derived here and is not a parameter** (memo §10.2). If a caller could pass
 * [ReportAudience.PHYSICIAN] for a community-tier viewer, the model would speak aloud exactly the
 * PRIVATE-ailment content [ReportAudience.WORKER] redaction exists to withhold (REQ-AIL-02), and
 * the H-17 prescription visibility gate would be laundered through generated prose. Deriving it
 * from the live session's [CadreTier] at this layer is what makes that impossible rather than
 * merely discouraged.
 *
 * Not built here, by design: no prompt, no engine, no sanitizer, no scope gate, no audit action,
 * no feature flag. This is the input contract only.
 */
class ApprovedRecordReader @Inject constructor(
    private val authSession: AuthSession,
    private val caseRecordRepository: CaseRecordRepository,
    private val assembleReportUseCase: AssembleReportUseCase,
) {
    suspend operator fun invoke(caseRecordId: String): ApprovedRecordResult {
        caseRecordRepository.observeCaseRecord(caseRecordId).first()
            ?: return ApprovedRecordResult.Refused(SnapshotRefusal.CASE_UNRESOLVABLE)

        val audience = authSession.currentUser().first().toReportAudience()

        // AssembleReportUseCase reports a missing row as a failed Result but a formatter refusal
        // (banned Latin abbreviation, REQ-RX-02) as a thrown IllegalArgumentException. Both are
        // "the report could not be built", and both must land on a refusal rather than propagate
        // to a caller that would have nothing to do with them.
        val report = try {
            assembleReportUseCase(caseRecordId, audience).getOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            null
        } ?: return ApprovedRecordResult.Refused(SnapshotRefusal.ASSEMBLY_FAILED)

        val decision = report.kernelDecision
            ?: return ApprovedRecordResult.Refused(SnapshotRefusal.NOT_APPROVED)

        return ApprovedRecordResult.Available(
            ApprovedRecordSnapshot(
                caseRecordId = caseRecordId,
                kernelDecision = decision,
                diagnosis = report.diagnosis,
                medicationLines = report.prescription.map { it.text },
                suggestsReferral = report.suggestsReferral,
            ),
        )
    }
}

/**
 * The only permitted source of a [ReportAudience] on the SLM path (memo §10.2). Total and
 * fail-closed: only [CadreTier.PHYSICIAN] gets [ReportAudience.PHYSICIAN], and a null session
 * (signed out, or a session read that raced a sign-out) is treated as the most restricted viewer
 * rather than as an error, so there is no path where "no viewer" reads an unredacted record.
 *
 * Deliberately `internal` and parameterless-by-viewer: nothing outside this module can hand the
 * reader an audience, and nothing inside it can pick one that is not the live viewer's.
 */
internal fun UserSession?.toReportAudience(): ReportAudience =
    if (this?.role?.toCadreTier() == CadreTier.PHYSICIAN) ReportAudience.PHYSICIAN else ReportAudience.WORKER
