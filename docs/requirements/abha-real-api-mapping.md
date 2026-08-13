# Real ABDM/ABHA API — integration planning map (not built yet)

> **Planning-only document.** No code exists against this. The app's ABHA flow today
> (`AbhaProfileRepository`, `CreateAbhaProfileUseCase`, `VerifyAbhaLoginUseCase`) is entirely
> **mocked and local** — see `docs/requirements/abha-field-mapping.md` for that mock's field
> shape. This document maps the real ABDM sandbox API surface (from `abha api docs/`) against
> the app's existing mock so a future integration phase has a concrete starting point instead of
> re-deriving it from the raw PDFs/Postman collections. Written per user instruction to lay out
> documentation before starting real ABHA work — **do not start real integration from this doc
> alone; it has not been reviewed against the current sandbox contract version.**

## Source material inventory (`abha api docs/`)
- `get started/` — conceptual docs: `aboutabdmsandbox.md`, `becomeabdmenabled.md`,
  `verifysandboxaccess.md`, `workingwithabdmapis.md` (auth header, client ID/callback URL,
  X-HIP-ID/X-HIU-ID/X-CM-ID headers, async callback pattern), `phrframework.md` (ABHA
  address/number, HIE-CM, HIP/HIU/HRP roles, consent flow), `encodingndrsaencryption.md`,
  `postmansetup.md`.
- Milestone docs (`M2_Document...docx`, `M3_Document...docx`) + Postman collections
  (`postman collection/Milestone_1…json`, `Milestone_2…postman_collection`,
  `Milestone_3…postman_collection`) — staged integration milestones, not yet read into this doc
  in detail (docx/binary — needs a follow-up pass with a docx reader).
- `ABDM_ABHA_V3_AP_Is_V1_31_07_2025_869ab8cda9.pdf` — the core ABHA v3 API spec.
- `Face_Auth_ABHA_Creation_Steps...pdf`, `Updated_Find_ABHA_Face_QR_Scan_API_Specification...pdf`
  — biometric/QR-based ABHA discovery, relevant since the app already has real biometric login
  for the *worker* (`BiometricPrompt`, unrelated session) — a natural analogue for face-auth
  ABHA lookup on the *patient* side.
- `Scan_and_share_Document...pdf` + `SBX_SCAN_PAY_V3...postman_collection.json` — QR scan-and-
  share flow (facility QR → PHR app → demographics incl. ABHA address).
- `NHPR_SBX_Doc_1.../NHPR Doc/` — Healthcare Professionals Registry (HPR) + Health Facility
  Registry (HFR) APIs: doctor/nurse registration, HPID, facility registration. Not yet mapped to
  anything in this app — `Doctor`/`DoctorEntity` here are still fully local/mock, no HPR link.
- `ABHA-Card-...pdf`, `Running_Token_Status_Documentation...pdf`, `FAQ...pdf`.

## Conceptual mapping — ABDM roles vs. this app
| ABDM role | Definition | This app's position |
|---|---|---|
| HIP (Health Information Provider) | Entity generating health records (facility) | The PHC this app runs at would become an HIP, obtaining an HFR ID |
| HRP (Health Repository Provider) | Software vendor whose instance holds records on behalf of the HIP | This app/backend would need to become HRP-certified to hold records long-term |
| HIU (Health Information User) | Entity requesting a patient's records with consent | Not currently a role this app plays — no cross-facility record discovery exists today |
| HIE-CM | ABDM's central address/consent broker | External; app never sees raw health data pass through it (federated architecture — data stays with the HRP) |

## Field mapping — real ABHA v3 KYC profile vs. `AbhaProfile` (mock)
The existing mock (`docs/requirements/abha-field-mapping.md`) was deliberately shaped to mirror
the real KYC `/profile` response. Confirmed still accurate at a conceptual level per
`phrframework.md`: ABHA address (`name@sbx`/`name@abdm`) or ABHA number (14-digit,
`<14digit>@abdm`), name, gender, year/date of birth, mobile, and structured address. **Not yet
verified line-by-line against the actual `ABDM_ABHA_V3_AP_Is` PDF's JSON schema** — that PDF
needs a dedicated read-through before real integration starts, since API field names/casing in
the real contract will differ from the app's Kotlin field names and the mock doc's informal
description.

## What real integration would newly require (not present in the mock)
1. **Client ID + secret + registered callback URL** (`workingwithabdmapis.md`) — this app has no
   backend today to host a callback endpoint; the mocked flow is entirely on-device/synchronous.
   Real ABDM APIs are **asynchronous** (HTTP 200 ack, then a callback). This is a structural
   mismatch with the current on-device `VerifyAbhaLoginUseCase` (mock OTP, synchronous) and
   needs a backend component before it can be real — ties into `docs/sync-design.md`'s existing
   "no backend yet" gap, not a new one.
2. **X-HIP-ID / X-HIU-ID / X-CM-ID headers** — this app/PHC would need an HFR ID (via
   `facility.abdm.gov.in` or the NHPR/HFR sandbox docs) before any real API call is legal.
3. **Consent-artifact handling** (`phrframework.md`) — every record link/share needs a signed
   consent artifact from the patient's PHR app. No consent-artifact concept exists in this app's
   domain model today; `AbhaProfile.kycVerified` is the closest existing field and is not the
   same thing.
4. **Care-context linking** — a real HIP must link every new record (registration, consultation,
   report) to the ABHA address as a "care context" (reference id + display text, no health data).
   No `CareContext` domain concept exists yet.
5. **Face-auth / QR scan-and-share** — as an *alternative* patient-identification path alongside
   the existing name+OTP mock flow; would extend `AbhaEntryScreen`'s Create/Login/Skip options,
   not replace them.

## Explicitly out of scope for now
- HPR/HFR doctor/facility registration integration — the doctor-continuity feature
  (`ResolveDoctorAssignmentUseCase`) and `DoctorEntity` stay fully local/mock; no plan to link to
  the real HPR registry in this pass.
- Any live sandbox API call. This document is a map for a future phase, not a phase itself.

## Next step if/when this phase starts
Read `ABDM_ABHA_V3_AP_Is_V1_31_07_2025_869ab8cda9.pdf` and the Milestone 1-3 Postman collections
in full, produce a field-by-field diff against `AbhaProfile`, and decide the backend/callback
architecture question (item 1 above) before writing any integration code — that decision gates
everything else in this list.
