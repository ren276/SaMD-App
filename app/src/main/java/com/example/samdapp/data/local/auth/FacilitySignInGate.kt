package com.example.samdapp.data.local.auth

import com.example.samdapp.data.local.LocalClinicalData
import com.example.samdapp.data.local.LocalTransaction
import com.example.samdapp.data.local.audit.RoomAuditLogger
import com.example.samdapp.data.local.dao.DeviceDataOwnerDao
import com.example.samdapp.data.remote.AuthApiResult
import com.example.samdapp.data.remote.RetrofitAuthService
import com.example.samdapp.data.sync.SyncOutboxRepository
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.auth.FacilitySignInDecision
import com.example.samdapp.domain.auth.SessionFacility
import com.example.samdapp.domain.auth.SignInOutbox
import com.example.samdapp.domain.auth.decideFacilitySignIn
import java.time.Instant
import javax.inject.Inject
import javax.inject.Provider
import kotlin.coroutines.cancellation.CancellationException

/** Whether a sign-in the server accepted may become a session on this phone. */
sealed interface FacilityAdmission {
    data object Admitted : FacilityAdmission

    data class Blocked(val ownerFacilityName: String) : FacilityAdmission

    data object NoFacility : FacilityAdmission

    /** The check could not complete safely; refused fail closed (FS-R5). */
    data object Unavailable : FacilityAdmission
}

/**
 * The facility check at sign-in (facility-scope memo 3.2, rulings FS-R and FS-R5), run after the
 * server accepts the PIN and before anything is saved.
 *
 * - CLAIM and LEGACY_CLAIM write the owner row and its audit row in one Room transaction: if the
 *   audit write fails, the claim rolls back and the sign-in is refused as [FacilityAdmission.Unavailable].
 * - CONTINUE admits.
 * - BLOCK and a login with no facility id revoke the new refresh token, save nothing, and are
 *   audited when the audit write succeeds; a failed audit never turns a refusal into an admit.
 * - Anything unexpected (a failed read, a lost claim with no owner afterwards) is refused as
 *   [FacilityAdmission.Unavailable], revoked and audited if possible.
 *
 * No exception leaves [admit] except cancellation. Audit rows are attributed to the signing-in
 * worker (`RoomAuditLogger.logAs`), with facility ids, the decision, the outbox counts and the
 * revoke result; never patient or record content. [auditLogger] is a [Provider] because
 * `RoomAuditLogger` depends on `AuthSession`, which depends on this gate.
 */
class FacilitySignInGate @Inject constructor(
    private val ownerDao: DeviceDataOwnerDao,
    private val clinicalData: LocalClinicalData,
    private val outbox: SyncOutboxRepository,
    private val transaction: LocalTransaction,
    private val authApiService: RetrofitAuthService,
    private val auditLogger: Provider<RoomAuditLogger>,
) {

    suspend fun admit(workerId: String, facilityId: String?, facilityName: String?, refreshToken: String): FacilityAdmission {
        var counts = SignInOutbox()
        return try {
            counts = outbox.readSignInOutbox()
            decideAndAct(workerId, facilityId, facilityName, refreshToken, counts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            refuse(workerId, facilityId.orEmpty(), null, "unavailable", counts, refreshToken, FacilityAdmission.Unavailable)
        }
    }

    private suspend fun decideAndAct(
        workerId: String,
        facilityId: String?,
        facilityName: String?,
        refreshToken: String,
        counts: SignInOutbox,
    ): FacilityAdmission {
        if (facilityId.isNullOrBlank()) {
            return refuse(workerId, "", ownerDao.read()?.facilityId, "no_facility", counts, refreshToken, FacilityAdmission.NoFacility)
        }
        val login = SessionFacility(facilityId, facilityName.orEmpty())
        var decision = decide(login, counts)
        if (decision is FacilitySignInDecision.Claim || decision is FacilitySignInDecision.LegacyClaim) {
            val legacy = decision is FacilitySignInDecision.LegacyClaim
            val claimed = transaction.run {
                ownerDao.claim(login.facilityId, login.facilityName, Instant.now(), workerId, legacy) && run {
                    auditLogger.get().logAs(
                        workerId, AuditAction.LOCAL_DATA_OWNER_CLAIMED,
                        auditPayload(
                            "decision" to if (legacy) "legacy_claim" else "claim",
                            "facility_id" to login.facilityId,
                            "legacy" to legacy.toString(),
                            *counts.auditFields(),
                        ),
                    )
                    true
                }
            }
            if (claimed) return FacilityAdmission.Admitted
            // A concurrent sign-in claimed the phone first: the owner exists now, so decide again,
            // once. That decision is CONTINUE or BLOCK; nothing in the app removes an owner.
            decision = decide(login, counts)
        }
        return when (decision) {
            FacilitySignInDecision.Continue -> FacilityAdmission.Admitted
            is FacilitySignInDecision.Block -> refuse(
                workerId, login.facilityId, ownerDao.read()?.facilityId, "block", counts, refreshToken,
                FacilityAdmission.Blocked(decision.ownerFacilityName),
            )
            // A lost claim and then no owner: a state the phone cannot be in. Fail closed.
            FacilitySignInDecision.Claim, FacilitySignInDecision.LegacyClaim ->
                refuse(workerId, login.facilityId, null, "unavailable", counts, refreshToken, FacilityAdmission.Unavailable)
        }
    }

    private suspend fun decide(login: SessionFacility, counts: SignInOutbox): FacilitySignInDecision {
        val owner = ownerDao.read()?.let { SessionFacility(it.facilityId, it.facilityName) }
        return decideFacilitySignIn(login, owner, clinicalData.present(), counts)
    }

    /**
     * Saves nothing; revokes the new refresh token; records the refusal when the audit write
     * succeeds. Always returns [result]: neither a failed revoke (FS-1(2)) nor a failed audit
     * (FS-R5) changes a refusal. Never throws, except cancellation.
     */
    private suspend fun refuse(
        workerId: String,
        loginFacilityId: String,
        ownerFacilityId: String?,
        decision: String,
        counts: SignInOutbox,
        refreshToken: String,
        result: FacilityAdmission,
    ): FacilityAdmission {
        val revoked = try {
            authApiService.logout(refreshToken) is AuthApiResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        try {
            auditLogger.get().logAs(
                workerId, AuditAction.CROSS_FACILITY_SIGN_IN_BLOCKED,
                auditPayload(
                    "decision" to decision,
                    "facility_id" to loginFacilityId,
                    "owner_facility_id" to ownerFacilityId,
                    "revoke" to if (revoked) "revoked" else "failed",
                    *counts.auditFields(),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The refusal stands without its row (FS-R5).
        }
        return result
    }

    private fun SignInOutbox.auditFields(): Array<Pair<String, String?>> = arrayOf(
        "pending_clinical" to pendingClinical.toString(),
        "held_clinical" to heldClinical.toString(),
        "pending_audit" to pendingAudit.toString(),
        "failed" to failed.toString(),
        "conflict" to conflict.toString(),
    )
}
