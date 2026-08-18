package com.example.samdapp.data.local.auth

import com.example.samdapp.domain.auth.UserRole
import java.security.MessageDigest

/**
 * `sha256(name.trim().lowercase() + "|" + role.name)`, first 16 hex characters. Shared by
 * [MockAuthSession] (unbound, kept for reference) and [BackendAuthSession] so both derive the
 * same value for the same name+role: this IS the audit-continuity mechanism across the
 * mock-to-real cutover (api-contract.md §2.1/§2.2): `user_accounts.worker_id` is provisioned
 * server-side to this exact value, so historical device-side audit rows and new server-side rows
 * for the same person share one identifier. A change here silently fractures every existing
 * worker's audit trail. Do not touch without updating the backend provisioning docs alongside it.
 */
fun stableUserId(name: String, role: UserRole): String {
    val normalized = "${name.trim().lowercase()}|${role.name}"
    val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }.take(16)
}
