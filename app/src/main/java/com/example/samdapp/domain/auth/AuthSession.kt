package com.example.samdapp.domain.auth

import kotlinx.coroutines.flow.Flow

/**
 * PHC field roles only — the admin/CMO dashboard is a separate product and out of scope here.
 * Matches the worker types already used in docs/regulatory-foundation.md and the app's own
 * screen names (Compounder), rather than inventing new terminology.
 */
enum class UserRole { ASHA_WORKER, NURSE, COMPOUNDER }

/**
 * [userId] is a locally-generated opaque id, not the person's name — same data-minimization
 * shape as [com.example.samdapp.domain.model.Patient.id]/[com.example.samdapp.domain.model.CaseRecord.id]:
 * an ID for correlation, not a display value copied into every audit row. [name]/[role] are kept
 * only in the session record itself (for greeting UI and the role picker), not written per-row.
 */
data class UserSession(val userId: String, val name: String, val role: UserRole)

/**
 * Current signed-in PHC worker, if any. This is identity for audit accountability, not
 * authentication — [signIn] performs no credential check. Real authentication + role-based
 * access enforcement is REQ-SEC-03, still PLANNED (see docs/requirements/software-requirements.md).
 */
interface AuthSession {
    fun currentUser(): Flow<UserSession?>
    suspend fun signIn(name: String, role: UserRole)
    suspend fun signOut()
}
