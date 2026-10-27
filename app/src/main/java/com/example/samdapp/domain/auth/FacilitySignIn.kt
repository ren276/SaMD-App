package com.example.samdapp.domain.auth

/**
 * What the outbox still owes the server at sign-in (facility-scope memo 3.2, FS-1(1)), folded from
 * the same shared predicates the drain uses: the twenty grouped state counts, with `SyncSql`'s
 * held-row constants and its `EVALUATE_SYNCABLE` exclusion. A local-only evaluate marker is never
 * pushed, so it is not in here and never blocks; it is still facility data and is wiped.
 *
 * [failed] and [conflict] together are Home's "needs review". FAILED and CONFLICT rows never leave
 * on their own, so they count as not sent.
 */
data class SignInOutbox(
    val pendingClinical: Int = 0,
    val heldClinical: Int = 0,
    val pendingAudit: Int = 0,
    val failed: Int = 0,
    val conflict: Int = 0,
) {
    val everythingSent: Boolean
        get() = pendingClinical == 0 && heldClinical == 0 && pendingAudit == 0 && failed == 0 && conflict == 0
}

/** The outcome of a sign-in against the phone's data owner (facility-scope memo 3.2). */
sealed interface FacilitySignInDecision {
    /** No owner and no clinical data: write the owner for the login's facility, continue. */
    data object Claim : FacilitySignInDecision

    /** The login's facility owns the data. */
    data object Continue : FacilitySignInDecision

    /** Another facility owns the data and all of it reached the server: wipe, claim, continue. */
    data object WipeThenClaim : FacilitySignInDecision

    /** Another facility owns data the server does not have: revoke the new login, save nothing.
     *  [ownerFacilityName] is for the copy, which names the facility whose worker must sign in. */
    data class Block(val ownerFacilityName: String) : FacilitySignInDecision

    /** Data and no owner: a phone upgraded from schema 22. Claimed with `legacy = true` (FS-4(a)). */
    data object LegacyClaim : FacilitySignInDecision
}

/**
 * Decides a sign-in from the login's facility, the owner marker ([owner], null when there is none),
 * whether the database holds any clinical row (local-only rows included), and the outbox state.
 * Facility ids compare exactly, never by name, as in `facilityScopeAllows`.
 *
 * A blank login facility id is refused rather than decided: claiming for it would give the phone
 * an owner no other login can match, and comparing it would treat every owner as foreign.
 */
fun decideFacilitySignIn(
    login: SessionFacility,
    owner: SessionFacility?,
    databaseHoldsClinicalRows: Boolean,
    outbox: SignInOutbox,
): FacilitySignInDecision {
    require(login.facilityId.isNotBlank()) { "the login carries no facility id" }
    return when {
        owner == null && databaseHoldsClinicalRows -> FacilitySignInDecision.LegacyClaim
        owner == null -> FacilitySignInDecision.Claim
        owner.facilityId == login.facilityId -> FacilitySignInDecision.Continue
        outbox.everythingSent -> FacilitySignInDecision.WipeThenClaim
        else -> FacilitySignInDecision.Block(owner.facilityName)
    }
}
