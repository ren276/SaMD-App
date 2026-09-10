package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.domain.model.KernelPayload
import com.example.samdapp.domain.model.SyncState
import com.example.samdapp.domain.model.toVitalsReading
import com.example.samdapp.domain.repository.CaseRecordRepository
import com.example.samdapp.domain.repository.ConsultationRepository
import com.example.samdapp.domain.repository.EncounterRepository
import com.example.samdapp.domain.repository.PatientRepository
import com.example.samdapp.domain.repository.VitalsRepository
import com.example.samdapp.domain.sync.OutboxDrainer
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.logging.Logger
import javax.inject.Inject

/**
 * The single orchestrator for a case's kernel + evaluate assessment, run from
 * [com.example.samdapp.data.assessment.AssessmentWorker] (the async submission queue) for both a
 * case's first assessment and every retry — the only place this logic lives, so the two paths
 * cannot drift apart.
 *
 * Resolution (case record, vitals, consultation, encounter, patient) is deliberately STRICT:
 * missing vitals or a missing consultation means the assessment cannot honestly run, and [run]
 * collapses straight to [GenerateKernelReportUseCase.recordUnavailable] rather than assessing on
 * a silently-substituted empty reading — never fabricate a result from data that isn't there.
 *
 * **Sync-before-assess gate:** the backend's `_resolve_case_record` 404s with `SAMD-ENC-4002`
 * when the case record is absent. Every clinical record (patient, encounter, consultation,
 * case_record) is device-minted and reaches the server only through `/sync/push`. [run] therefore
 * drains the outbox in-process via [outboxDrainer] before the kernel call and reads the
 * case_record's persisted transport `syncState`; anything other than [SyncState.SYNCED] short-
 * circuits to a recorded UNAVAILABLE instead of a doomed network round trip. A drain failure is
 * best-effort: the state read, not the drain's `Result`, is what the gate trusts.
 *
 * **This gate is NOT a transient condition, and it is not only a race.** It covers two distinct
 * populations, and conflating them is the mistake this KDoc exists to prevent:
 *
 *  - [SyncState.PENDING], usually transient. The record simply has not reached the server yet
 *    (offline, drain not run, oversized batch). A later run drains it, the state becomes SYNCED,
 *    and the assessment proceeds. This is the race the gate was originally written for.
 *  - [SyncState.FAILED], **terminal**. A case whose parent patient was rejected by the server
 *    (duplicate ABHA on `ix_patients_abha_number`, `SAMD-SYNC-6003`) lands FAILED, and its
 *    children fail their FK and land FAILED too. The outbox collects **only** `PENDING` rows
 *    (`CaseRecordDao`, `PatientDao`), and nothing anywhere requeues a FAILED row: there is no
 *    retry, no backoff, no operator path. [outboxDrainer] therefore collects nothing for such a
 *    case on this run or any future run, and the gate reads FAILED forever. For that whole class
 *    of cases the UNAVAILABLE result is **PERMANENT**, not a "try again later".
 *
 * So this gate is a symptom fix, not a root-cause fix. For the terminal-FAILED population it does
 * not change the clinical outcome at all (still permanently UNAVAILABLE), it only reaches that
 * outcome faster and without a wasted round trip. The root cause, that a FAILED row is terminal
 * with no requeue and no device-side way to distinguish "someone else holds this ABHA" from "the
 * network is down", is untouched and still open. See
 * `scratchpad/AUDIT4-offline-identity-sync-classifier.md` and
 * `scratchpad/AUDIT5-antigravity-uncommitted-work.md` section 2.4.
 */
class AssessmentRunner @Inject constructor(
    private val caseRecordRepository: CaseRecordRepository,
    private val vitalsRepository: VitalsRepository,
    private val consultationRepository: ConsultationRepository,
    private val encounterRepository: EncounterRepository,
    private val patientRepository: PatientRepository,
    private val sendToKernelUseCase: SendToKernelUseCase,
    private val generateKernelReportUseCase: GenerateKernelReportUseCase,
    private val generateEvaluateReportUseCase: GenerateEvaluateReportUseCase,
    private val auditLogger: AuditLogger,
    private val outboxDrainer: OutboxDrainer,
    /** Reads the case_record's transport syncState, which is a data-layer concern (not on the
     *  domain [com.example.samdapp.domain.model.CaseRecord] model). Injected as a function type
     *  to avoid pulling the DAO into this domain-layer class directly. */
    private val getCaseRecordSyncState: GetCaseRecordSyncState,
) {
    private val gson = Gson()

    private data class Resolved(
        val payload: KernelPayload,
        val consultationId: String,
        val patientId: String?,
        val patientAge: Int?,
        val patientSex: String?,
    )

    suspend fun run(caseRecordId: String) {
        // ── Stage 0: sync-before-assess gate ────────────────────────────────────
        // Drain the outbox in-process so the case_record (and its parents: patient, encounter)
        // reach the server before we call /api/v1/assess. Best-effort: if the drain fails
        // (network, auth, oversized), the assess call proceeds and the backend's 404 produces
        // the same UNAVAILABLE state as before this fix.
        try {
            outboxDrainer.drainAll().onFailure { e ->
                logger.warning("Pre-assess outbox drain failed (best-effort, continuing): ${e.message}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warning("Pre-assess outbox drain threw (best-effort, continuing): ${e.message}")
        }

        // After drain, read the case_record's persisted transport state. The drain's own Result
        // is deliberately NOT trusted here (it is only logged above): the Room column is the
        // evidence, a returned success is not.
        //
        // Not-SYNCED does not prove the server lacks the record, so this is NOT a "guaranteed
        // 404". syncState is reset to PENDING by any local clinical mutation (CaseRecordDao),
        // so a record the server already holds reads PENDING again after an edit; if the drain
        // then fails for a reason unrelated to reachability (an unrefreshable 401, a corrupt
        // in-flight batch store, an oversized sibling), this gate records UNAVAILABLE for a case
        // the backend could in fact have assessed. That trade is accepted deliberately: a false
        // UNAVAILABLE is an honest "we could not assess", whereas the pre-gate behaviour burned
        // a round trip and, on a dev build, could fall through to a MOCK_FALLBACK result.
        // Narrowing it needs a server-presence signal the device does not have today.
        val syncState = try {
            getCaseRecordSyncState(caseRecordId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warning("Could not read case_record syncState: ${e.message}")
            null // Unknown — proceed to the assess call and let the backend decide.
        }
        if (syncState != null && syncState != SyncState.SYNCED) {
            logger.warning(
                "Case $caseRecordId syncState is $syncState (not SYNCED) after outbox drain — " +
                    "backend will 404; recording UNAVAILABLE."
            )
            recordUnavailableAudited(caseRecordId, reason = "case_record_not_synced", syncState = syncState)
            return
        }

        // ── Stage 1–2: resolve local data and build payload ─────────────────────
        // The one catch in this class: it wraps resolution and payload build only (stage 1/2),
        // and converts both their strict null returns and any unexpected exception into the same
        // branch. Stage 3 (kernel) never throws and already falls through to its own unavailable
        // state internally; stage 4 (evaluate) already writes its own failure marker and must be
        // audited, not swallowed. Neither is wrapped here.
        val resolved = try {
            resolve(caseRecordId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warning("Assessment resolve/build failed for case $caseRecordId: ${e.message}")
            null
        }

        if (resolved == null) {
            recordUnavailableAudited(caseRecordId, reason = "local_data_unresolvable")
            return
        }

        // ── Stage 3: kernel assess ──────────────────────────────────────────────
        val kernelResult = generateKernelReportUseCase(
            caseRecordId = caseRecordId,
            payload = resolved.payload,
            patientAge = resolved.patientAge,
            patientSex = resolved.patientSex,
        )

        // ── Stage 4: evaluate (NLEM / treatment) ────────────────────────────────
        // Fired alongside the kernel call above — a distinct clinical concern (NLEM
        // treatment/brand-mapping/vitals-triage), no mock fallback, failure just means the report
        // screen omits that section (see GenerateEvaluateReportUseCase KDoc).
        val evaluateResult = generateEvaluateReportUseCase(
            caseRecordId = caseRecordId,
            payload = resolved.payload,
            patientAge = resolved.patientAge,
            patientSex = resolved.patientSex,
        )

        evaluateResult.onSuccess { output ->
            auditLogger.log(
                action = AuditAction.EVALUATE_RESPONSE_RECEIVED,
                patientId = resolved.patientId,
                caseRecordId = caseRecordId,
                payload = gson.toJson(output),
            )
        }.onFailure { e ->
            auditLogger.log(
                action = AuditAction.EVALUATE_RESPONSE_FAILED,
                patientId = resolved.patientId,
                caseRecordId = caseRecordId,
                payload = auditPayload("error" to e.message),
            )
        }

        auditLogger.log(
            action = AuditAction.KERNEL_RESPONSE_RECEIVED,
            caseRecordId = caseRecordId,
            payload = auditPayload(
                "consultationId" to resolved.consultationId,
                "inferenceSource" to kernelResult.getOrNull()?.inferenceSource?.name,
            ),
        )
    }

    /**
     * The ONLY way an early return may record an UNAVAILABLE result.
     *
     * [GenerateKernelReportUseCase.recordUnavailable] persists a clinical artifact (a kernel
     * report row), and in a Class B/C SaMD with an append-only audit log a persisted clinical
     * artifact must never exist without a corresponding audit entry. Both early returns in [run]
     * used to write that row and return without logging anything, which silently removed the
     * audit trail from what the Stage-0 gate makes the DOMINANT UNAVAILABLE path in the field
     * ("not synced" and "offline" are the same condition). See AUDIT5 section 2.5(a).
     *
     * [AuditAction.KERNEL_RESPONSE_RECEIVED] is deliberately the same action [run] emits at the
     * bottom of the full path, carrying the same `inferenceSource` key. Before the Stage-0 gate
     * existed, an offline assessment ran all four stages and emitted exactly this action with
     * `inferenceSource = UNAVAILABLE`; reusing it means the gate restores the pre-existing audit
     * shape rather than inventing a second vocabulary for the same clinical outcome. [reason]
     * (and [syncState], where the sync gate is what fired) is what tells them apart.
     */
    private suspend fun recordUnavailableAudited(
        caseRecordId: String,
        reason: String,
        syncState: SyncState? = null,
    ) {
        generateKernelReportUseCase.recordUnavailable(caseRecordId)
        auditLogger.log(
            action = AuditAction.KERNEL_RESPONSE_RECEIVED,
            caseRecordId = caseRecordId,
            payload = auditPayload(
                "inferenceSource" to InferenceSource.UNAVAILABLE.name,
                "reason" to reason,
                "syncState" to syncState?.name,
            ),
        )
    }

    /** Null means the assessment cannot honestly run: no case record, no vitals, no consultation,
     *  or [sendToKernelUseCase] could not build a payload. Encounter and patient are soft reads —
     *  a missing patient still assesses, using [GenerateKernelReportUseCase]'s age/sex defaults,
     *  same as the callers this replaces. */
    private suspend fun resolve(caseRecordId: String): Resolved? {
        val caseRecord = caseRecordRepository.observeCaseRecord(caseRecordId).first() ?: return null
        val encounterId = caseRecord.encounterId

        val vitals = vitalsRepository.observeLatestForEncounter(encounterId).first()?.toVitalsReading()
            ?: return null
        val consultation = consultationRepository.observeForEncounter(encounterId).first() ?: return null
        val encounter = encounterRepository.observeEncounter(encounterId).first()
        val patient = encounter?.patientId?.let { patientRepository.observePatient(it).first() }

        val payload = sendToKernelUseCase(vitals = vitals, consultation = consultation, caseToken = caseRecordId)
            .getOrNull() ?: return null

        return Resolved(
            payload = payload,
            consultationId = consultation.id,
            patientId = patient?.id,
            patientAge = patient?.age,
            patientSex = patient?.biologicalSex,
        )
    }

    private companion object {
        val logger = Logger.getLogger("AssessmentRunner")
    }
}
