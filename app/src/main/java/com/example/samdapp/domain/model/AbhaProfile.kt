package com.example.samdapp.domain.model

import java.time.Instant
import java.time.LocalDate

/**
 * A patient's ABHA (Ayushman Bharat Health Account) profile — the identity source that precedes
 * PHC registration. In the real ABDM flow the fields below are returned, KYC-verified, from the
 * Aadhaar-OTP profile endpoint and used to autofill registration; here they are captured via the
 * mock ABHA flow (Phase 1). Registration reads from this profile, it does not re-key the identity.
 *
 * [abhaId] is the canonical 14-digit ABHA number, digits only — same shape as the existing
 * [Patient.abhaNumber] field (REQ-REG-02) so the two can hold the identical value and actually
 * link (no duplicate id column on Patient). Format with [formatAbhaId] for display only
 * (`XX-XXXX-XXXX-XXXX`, e.g. `43-4221-5105-6749`, matching the NHA API doc example) — never store
 * the dashed form.
 *
 * Field set mirrors what the real ABDM `/profile` KYC response carries (name, gender, DOB, mobile,
 * email, structured address, photo, verification flag) so the mock autofill maps 1:1 to the real
 * one — see docs/requirements/abha-field-mapping.md.
 */
data class AbhaProfile(
    val abhaId: String,
    val abhaAddress: String?,
    val name: String,
    val dateOfBirth: LocalDate?,
    val gender: String,
    val address: String?,
    val district: String?,
    val state: String?,
    val pincode: String?,
    val mobileNumber: String?,
    val emailAddress: String?,
    val photoUrlMock: String?,
    val kycVerified: Boolean,
    val createdAt: Instant,
)

/** Display-only formatting of a canonical 14-digit [AbhaProfile.abhaId] as `XX-XXXX-XXXX-XXXX`. */
fun formatAbhaId(rawAbhaId: String): String =
    if (rawAbhaId.length == 14) {
        "${rawAbhaId.substring(0, 2)}-${rawAbhaId.substring(2, 6)}-${rawAbhaId.substring(6, 10)}-${rawAbhaId.substring(10, 14)}"
    } else {
        rawAbhaId
    }
