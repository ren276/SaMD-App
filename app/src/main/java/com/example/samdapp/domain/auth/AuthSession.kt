package com.example.samdapp.domain.auth

import kotlinx.coroutines.flow.Flow

/**
 * PHC field roles only — the admin/CMO dashboard is a separate product and out of scope here.
 * Matches the worker types already used in docs/regulatory-foundation.md and the app's own
 * screen names (Compounder), rather than inventing new terminology. DOCTOR added in Phase 6a
 * (backend-prd.md D-2): the role the physician AGREE/MODIFY/REJECT decision (H-02) must be
 * attributable to. Adding the constant is in scope here; gating any screen on it is not.
 */
enum class UserRole { ASHA_WORKER, NURSE, COMPOUNDER, DOCTOR }

/**
 * [userId] is a locally-generated opaque id, not the person's name — same data-minimization
 * shape as [com.example.samdapp.domain.model.Patient.id]/[com.example.samdapp.domain.model.CaseRecord.id]:
 * an ID for correlation, not a display value copied into every audit row. [name]/[role] are kept
 * only in the session record itself (for greeting UI and the role picker), not written per-row.
 * [userId] is deterministically derived from name+role (see [com.example.samdapp.data.local.auth.MockAuthSession]),
 * so the same worker signing in again gets the same userId — this is a consistency mechanism,
 * not identity verification.
 */
data class UserSession(val userId: String, val name: String, val role: UserRole)

/** Result of [AuthSession.signIn]. Errors are returned, not thrown: mirrors [LoginUiState]'s
 *  existing errorMessage-string pattern rather than requiring every caller to catch. */
sealed interface SignInResult {
    /** [mustChangePin] mirrors the backend's `must_change_pin` flag (api-contract.md §2.2): the
     *  session is created either way, but the caller must route to PIN-change before anything else
     *  when true. */
    data class Success(val mustChangePin: Boolean) : SignInResult
    data class Failure(val message: String) : SignInResult
}

/** Result of [AuthSession.changePin]. */
sealed interface ChangePinResult {
    data object Success : ChangePinResult
    data class Failure(val message: String) : ChangePinResult
}

/**
 * Current signed-in PHC worker, if any.
 *
 * As of Phase 6a this is real authentication (REQ-SEC-03 closed, H-06 mitigated): [signIn]
 * verifies a PIN against the backend rather than trusting whatever name/role is typed. See
 * [com.example.samdapp.data.remote.RetrofitAuthService] and
 * [com.example.samdapp.data.local.auth.BackendAuthSession].
 */
interface AuthSession {
    fun currentUser(): Flow<UserSession?>

    /** True from a successful [signIn] with `must_change_pin` set until a change-pin call
     *  succeeds. Persisted so app restart before completing the change still routes correctly
     *  (api-contract.md §2.2: "every endpoint except /auth/me, /auth/change-pin, /auth/logout
     *  returns 403/SAMD-AUTH-1008" until this clears). */
    fun mustChangePin(): Flow<Boolean>

    suspend fun signIn(name: String, role: UserRole, pin: String): SignInResult

    /** Also the voluntary path from Profile (§2.6), not just the forced first-login one: the
     *  caller decides when to show the screen, this just performs the call. */
    suspend fun changePin(currentPin: String, newPin: String): ChangePinResult

    suspend fun signOut()
}
