package com.example.samdapp.domain.abha

import java.time.Instant

/**
 * Domain boundary for the real ABDM-backed ABHA registration flow — mirrors the "named mock
 * boundary" pattern used by [com.example.samdapp.domain.kernel.RemoteKernelSource]. Backs the
 * `POST /api/v1/abha/registration-sessions*` surface (api-contract.md §8), P0 only: existing-ABHA
 * login (`verification-sessions`) is explicitly P1 and has no real endpoint yet
 * (`docs/requirements/abha-internal-contract.md`'s "what Phase B must not build" list), so this
 * port has no login-side methods.
 *
 * Implementations:
 * - [com.example.samdapp.data.remote.RetrofitAbhaSource] — calls the real backend.
 *
 * **Phase 6c status (2026-08-18): this port is wired end to end (Retrofit, DI, this interface)
 * but NOT yet called by [com.example.samdapp.domain.usecase.CreateAbhaProfileUseCase].** That
 * use case's existing form (name/DOB/gender/mobile, no Aadhaar field, no OTP step) cannot drive
 * this state machine without a UI change the brief explicitly said not to make. See PROGRESS.md's
 * Phase 6c Part 2 entry for the full account of this decision. This interface, its DTOs, and
 * [com.example.samdapp.data.remote.RetrofitAbhaSource] are the infrastructure a future session
 * wires the use case to, once the UI question is resolved.
 */
interface AbdmAbhaSource {
    /** `POST registration-sessions` — starts a fresh transaction. Takes no input. */
    suspend fun startRegistrationSession(): AbhaApiResult<AbhaSessionSnapshot>

    /** `POST registration-sessions/{id}/identity` — submits the Aadhaar number; the backend
     *  encrypts it and requests an OTP. [aadhaarNumber] must be exactly 12 digits
     *  (api-contract.md §8's validation note); this port does not itself validate the shape,
     *  the caller does, same division of labor as every other use case in this app. */
    suspend fun submitIdentity(sessionId: String, aadhaarNumber: String): AbhaApiResult<AbhaSessionSnapshot>

    /** `POST registration-sessions/{id}/otp` — verifies the Aadhaar OTP and enrols. Resulting
     *  state is either `ENROLLED` or `MOBILE_VERIFICATION_REQUIRED` (api-contract.md §8). */
    suspend fun verifyOtp(sessionId: String, otp: String): AbhaApiResult<AbhaSessionSnapshot>

    /** `POST registration-sessions/{id}/mobile-otp` — the conditional second OTP step, only
     *  reachable from `MOBILE_VERIFICATION_REQUIRED`. */
    suspend fun verifyMobileOtp(sessionId: String, otp: String): AbhaApiResult<AbhaSessionSnapshot>

    /** `GET registration-sessions/{id}` — polls the current state, for a caller that needs to
     *  wait out an async step rather than always transitioning off a POST response directly. */
    suspend fun getSessionState(sessionId: String): AbhaApiResult<AbhaSessionSnapshot>

    /** `GET registration-sessions/{id}/profile` — the final verified identity. Only meaningful
     *  once [AbhaSessionSnapshot.state] is [AbhaTransactionState.PROFILE_RETRIEVED] or later. */
    suspend fun getProfile(sessionId: String): AbhaApiResult<AbhaIdentity>
}

/** Outcome of one [AbdmAbhaSource] call — deliberately the same shape as
 *  [com.example.samdapp.data.remote.AuthApiResult]: [Failure.code] is a `SAMD-ABHA-2xxx` code
 *  from api-contract.md §9.1, present when the backend was reached and structurally rejected the
 *  call (wrong OTP, expired session, out-of-order transition), `null` when the backend was never
 *  reached at all (connectivity failure). A future caller MUST branch on this distinction rather
 *  than treating every [Failure] as "fall back to mock": a wrong OTP is a real, actionable error
 *  the worker should retry, not grounds to fabricate a fake verified identity. Only a `null`-code
 *  failure (genuinely unreachable backend, e.g. a contract-only dev build with nothing running at
 *  `BACKEND_BASE_URL`) is safe to treat the way [com.example.samdapp.domain.usecase.GenerateKernelReportUseCase]
 *  treats every kernel failure, because a wrong ML inference confidence has no identity-fraud
 *  consequence and a fabricated ABHA identity does. */
sealed interface AbhaApiResult<out T> {
    data class Success<T>(val data: T) : AbhaApiResult<T>
    data class Failure(val code: String?, val message: String) : AbhaApiResult<Nothing>
}

/** Mirrors the backend's `AbhaTransactionState` (`app/models/enums.py`, confirmed unchanged by
 *  `abha-internal-contract.md`'s Phase B verification). */
enum class AbhaTransactionState {
    STARTED,
    IDENTITY_SUBMITTED,
    OTP_REQUESTED,
    OTP_VERIFIED,
    ENROLLED,
    MOBILE_VERIFICATION_REQUIRED,
    MOBILE_VERIFIED,
    PROFILE_RETRIEVED,
    COMPLETED,
    FAILED,
    EXPIRED,
}

/** One point-in-time read of a registration session — the shape every state-transition endpoint
 *  and the poll endpoint return. [maskedMobile] is only ever populated at [AbhaTransactionState.OTP_REQUESTED]
 *  (the `identity` response, api-contract.md §8) and is never a usable contact number — see
 *  `AbhaProfile.kt`'s `isMaskedAbhaMobile` for the same masking concern on the final profile. */
data class AbhaSessionSnapshot(
    val sessionId: String,
    val state: AbhaTransactionState,
    val maskedMobile: String? = null,
    val expiresAt: Instant? = null,
    val lastError: String? = null,
)

/** Domain-typed mirror of the `AbhaIdentity` wire object (api-contract.md §8), field for field —
 *  see `docs/requirements/abha-field-mapping.md` for how this maps onto `Patient`/registration
 *  autofill, and that same doc's `mobileNumber` row for why [mobileNumber] must never be treated
 *  as satisfying REQ-REG-01 on its own once it is real (masked) data, not the mock's fabricated
 *  full number. */
data class AbhaIdentity(
    val abhaNumber: String,
    val abhaAddress: String?,
    val name: String,
    val dateOfBirth: java.time.LocalDate?,
    val gender: String,
    val address: String?,
    val district: String?,
    val state: String?,
    val pincode: String?,
    val mobileNumber: String?,
    val emailAddress: String?,
    val photoUrl: String?,
    val kycVerified: Boolean,
    val verificationSource: String,
    val verifiedAt: Instant,
)
