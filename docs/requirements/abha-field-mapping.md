# ABHA → Registration field mapping (mock)

> Backs **REQ-ABH-01/02**. Defines which `AbhaProfile` fields autofill the PHC registration form
> (Phase 1). The mock flow is entirely local — no real ABDM sandbox call — but the field set mirrors
> the real Aadhaar-OTP KYC `/profile` response so the mock maps 1:1 to the eventual real one.
> ABHA precedes registration: the profile is the identity source, registration reads from it.

## Source of `AbhaProfile` fields (real ABDM equivalent)

The real ABDM KYC profile returns, among others: ABHA number, ABHA address (PHR address), name,
gender, date/year of birth, mobile, email, profile photo, and structured address
(address line, district/state name, pincode), plus a KYC-verified flag. `AbhaProfile` captures that
set (mocked) so nothing about the autofill shape has to change when the real gateway is wired.

## Autofill map

| `AbhaProfile` field | → Registration / `Patient` field | Notes |
|---------------------|-----------------------------------|-------|
| `abhaId`            | `Patient.abhaNumber`              | Link key; canonical 14 digits, same shape as `Patient.abhaNumber`. Display-formatted as `XX-XXXX-XXXX-XXXX` via `formatAbhaId()`, never stored dashed. No duplicate id column. |
| `name`              | `Patient.fullName`                | Tag "from ABHA" in UI. |
| `dateOfBirth`       | `Patient.dateOfBirth` (+ derive `age`) | |
| `gender`            | `Patient.biologicalSex`           | Map ABDM gender code → app value. |
| `mobileNumber`      | `Patient.mobileNumber`            | Satisfies the ≥1 contact-method rule (REQ-REG-01) **in the mock only**. **2026-08-17, real backend adapter (D4, `docs/requirements/abha-internal-contract.md`):** ABDM's real `profile/account` response never returns a full mobile number, only a masked one (e.g. `"******0903"`), by design, not a gap. The real `AbhaIdentity.mobile_number` the backend returns will carry that masked value or `null`, never a fabricated full number. A masked number cannot satisfy REQ-REG-01's contact-method rule. **Phase 6 consequence, not fixed here:** when the Android side is wired to the real backend, `mobileNumber` autofill must stop being treated as satisfying the contact-method requirement on its own; the worker still has to collect a real, usable contact number manually (or confirm the already-known one) as part of registration, the same as any patient with no ABHA-sourced mobile at all. |
| `address`           | `Patient.village`/free address    | ABDM address line. |
| `district`          | `Patient.district`                | |
| `state`             | `Patient.state`                   | |
| `pincode`           | `Patient.pincode`                 | |
| `emailAddress`      | *(no Patient field yet)*          | Capture-only; add a Patient field if ever needed. |
| `photoUrlMock`      | *(display only)*                  | Mock avatar; not persisted on Patient. |
| `kycVerified`       | *(display badge)*                 | Drives the "from ABHA / KYC-verified" tag. |

## Not autofilled (PHC-specific — collected after the ABHA step)

Aadhaar number, guardian name/relation (minors), block, category, marital status, blood group,
emergency contact, known allergies, primary-care clinic / referring physician, and the full
medical-background section. These have no ABHA source and stay manual (Phase 1 shows them after the
ABHA-derived block).

## Rules

- Autofilled fields are visually distinct in the form (a "from ABHA" tag) so a reviewer can see the
  data provenance during the demo.
- The ABHA (patient) session is separate from the worker mock login (REQ-SEC-04) — do not conflate
  the two session types.
