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

/** Same shape as [formatAbhaId] but masks every digit except the last 4 (e.g. `XX-XXXX-XXXX-6749`)
 *  — used on the clinical report, which leaves the patient's device/hands and shouldn't carry the
 *  full ABHA number in the clear. */
fun maskAbhaId(rawAbhaId: String): String =
    formatAbhaId(rawAbhaId).let { formatted ->
        val visible = 4
        formatted.mapIndexed { i, c -> if (c != '-' && i < formatted.length - visible) 'X' else c }.joinToString("")
    }

/**
 * True when [mobileNumber] is a masked value (e.g. `"XXXXXX3210"`, `"******0903"`) rather than a
 * usable, complete phone number. The real ABDM `/profile` response never returns a full mobile
 * number, only a masked one — the mock's fabricated full number is what let the old autofill
 * silently satisfy REQ-REG-01's contact-method rule; see
 * `docs/requirements/abha-field-mapping.md`'s `mobileNumber` row. A real 10-digit Indian mobile
 * number is all digits, so any non-digit character (the mask character, whatever ABDM uses for
 * it) is sufficient to detect a masked value — no need to hardcode `X` vs `*`. Blank/null is not
 * "masked," it is simply absent.
 */
fun isMaskedAbhaMobile(mobileNumber: String?): Boolean =
    !mobileNumber.isNullOrBlank() && !mobileNumber.all(Char::isDigit)

/**
 * [AbhaProfile.gender] normalised onto the app's own biological-sex vocabulary, the three options
 * the registration form offers (`RegisterScreen`'s selector: `"Female"`, `"Male"`, `"Other"`).
 * Returns null when the stored value maps to none of them, which callers treat as "leave the
 * field alone", never as a reason to default to one.
 *
 * Two vocabularies genuinely reach this function:
 * - The real ABDM `/profile/account` response returns a single letter, `"F"`/`"M"`/`"O"`, copied
 *   through unchanged by the backend (`docs/requirements/abha-internal-contract.md`'s field diff,
 *   `gender` row: "single-letter code, direct copy").
 * - The Phase 1 mock create/login flow stores whatever the worker picked in the sign-up form,
 *   which is already one of the three full words (see `AbhaSignUpViewModel`'s `gender`).
 *
 * Before this existed, `RegisterViewModel` compared the stored value directly against the three
 * full words, so a real `"F"` never matched and gender silently failed to autofill. That was
 * broken under `ABDM_MODE=stub` too, since the stub profile also returns `"F"`.
 *
 * ABDM's undisclosed/unknown code maps to null on purpose. `"Other"` is a stated answer, not a
 * placeholder for a missing one, and autofilling it would put an unstated value into a clinical
 * field the worker would then have no prompt to correct.
 */
fun abhaGenderToBiologicalSex(gender: String?): String? =
    when (gender?.trim()?.uppercase()) {
        "F", "FEMALE" -> "Female"
        "M", "MALE" -> "Male"
        "O", "OTHER" -> "Other"
        else -> null
    }
