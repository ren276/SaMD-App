# Intended Use Statement — PHC Patient Care (SaMD)

> **Draft for QA/RA review, not a submitted regulatory artifact.** Structured per CDSCO Guidance
> Document on Medical Device Software (Doc No. CDSCO/MD/GD/MDSW/01/2026) §6.0, which requires
> this statement to back the classification argument in `docs/regulatory-foundation.md` §2.3 and
> any future CDSCO submission. Every element below is drawn from what the app actually does
> today (2026-08) — nothing aspirational is included; gaps are flagged, not filled in.

## a) Medical purpose
Screening/triage and diagnostic decision support: capture of patient history, vitals, and
consultation data at the point of care, followed by AI-assisted differential diagnosis and
NLEM-mapped treatment recommendation, reviewed and finalised by a licensed doctor.

## b) Intended disease or condition
Not disease-specific. Scoped to the presentation types the kernel's training data and scenario
tables currently cover (fever, respiratory, GI, headache, hypertension, Type 2 diabetes, and the
broader specialty-routing list in `ResolveDoctorAssignmentUseCase`). Both acute (e.g. respiratory
infection) and chronic (e.g. diabetes, hypertension) conditions are in scope. **Gap:** no formal,
CDSCO-facing enumerated condition list exists yet — the scenario table is an engineering
artifact, not a regulatory-reviewed scope statement.

## c) Intended patient population
General adult and paediatric population presenting at a rural Primary Health Centre. No age
floor/ceiling enforced in the app today. **Gap:** paediatric-specific safety review not yet done;
`DemoPatientProfile` personas are adult-only.

## d) Intended users
**Non-clinical, non-specialist users**: ASHA worker, nurse, compounder (`UserRole` in
`AuthSession`) — ground-truth relevant to the CDSCO classification note that non-clinical-user
use in a serious situation may be treated as critical (see `docs/regulatory-foundation.md` §2.3).
A licensed doctor is the second, asynchronous user (AGREE/MODIFY/REJECT review) but is not
co-located with the frontline worker at the point of AI-output generation.

## e) Intended use environment
Rural Primary Health Centres (PHC) in India, offline-first field conditions with intermittent
connectivity. Not intended for home use, hospital ICU/critical-care settings, or specialist
clinics.

## f) Contraindications
None formally established. **Gap, flagged not fabricated:** no clinical review has identified
comorbidities or conditions where the kernel's output is known to be unreliable. The emergency
red-flag override (SpO2<90%, BP thresholds) is a safety net, not a substitute for a
contraindications analysis.

## g) MDSW device software function
- **Inputs:** worker-entered patient history, vitals, chief complaint/consultation text and
  audio, attachments (photos/audio/video) — via `KernelPayload` (pseudonymized, no identity
  fields by construction, see H-10 in `docs/quality/risk-management-file.md`).
- **Outputs:** ranked differential diagnosis with confidence (`/v1/assess`), NLEM-mapped
  drug/dosage/brand recommendation and vitals-triage grading (`/api/v1/evaluate`) — this is a
  **workflow/treatment recommendation** output, not a definitive diagnosis; final diagnosis and
  prescription are the doctor's, recorded via `SubmitDoctorDecisionUseCase`.
- **Clinical workflow fit:** output **drives** clinical management (aids diagnosis, recommends
  treatment, triages urgency) rather than merely informing it — this is the basis for the
  "Drive clinical management" column in the CDSCO Table 2 classification, see
  `docs/regulatory-foundation.md` §2.3. It does not act autonomously; every output requires
  explicit doctor AGREE/MODIFY/REJECT before it becomes a final prescription.

## h) Software platform
Android mobile application (general-purpose consumer hardware), communicating over HTTP to a
cloud/LAN-hosted FastAPI + XGBoost inference backend and a third-party Gemini API (best-effort
brand-name lookup only, never blocking, never patient-identifying data). No hardware medical
device interfacing.

## i) Speech-to-text (ASR) as a documentation aid [PROPOSED, AWAITING OPERATOR SIGN-OFF]
**Drafted this session (`fix/asr-offdevice-exposure` PR 0b) from `scratchpad/asr-usecase-research-memo.md`
sections C.1 and the FORMATTING BOUNDARY STATEMENT. Not yet operator-approved. As of this same
session the voice affordance itself is disabled (`FeatureFlags.VOICE_INPUT_ENABLED = false`); this
paragraph describes the intended framing for when it is re-enabled, not current shipped behavior.**

The speech-to-text function is a documentation aid. It proposes text for a human to read and
affirmatively accept into a field. It performs no clinical interpretation, produces no diagnostic
or triage output, and no value it proposes can be persisted or transmitted without an explicit
human confirmation recorded in the audit trail. Its failure mode is a wrong word in a narrative
field that a clinician reads, not a wrong clinical conclusion.

Automatic processing of an ASR transcript is limited to deterministic formatting that changes the
form of the text and never its content. A transformation is permitted only if it is deterministic,
versioned, content-preserving, salience-neutral, and negation-inert: it may not add, remove,
reorder across a clause boundary, or substitute any token carrying clinical meaning, and it may
not alter, attach, detach, or rescope a negation. Extraction of narrative into structured slots,
negation resolution, salience selection, summarisation, and any model-authored rewrite are never
automatic: they are interpretation, not formatting, and auto-applying them would remove the human
from the step at which a model's reading becomes the record, which is the premise this
classification argument (§g, `docs/regulatory-foundation.md` §2.3) rests on.

If extraction is ever offered, it may exist only as a constrained-decoding suggestion that emits
either a member of a fixed enumeration or a contiguous span of the verbatim transcript, that lands
in an unconfirmed state, that a human accepts one slot at a time against the retained verbatim
transcript displayed alongside it, and that emits a provenance-only audit breadcrumb on every
suggest, accept, edit, and reject. The verbatim transcript is retained as encrypted clinical data
and is the source of truth; the audio is not retained; the audit log carries provenance only and
never content.

## Open items before this can back a real submission
- CDSCO/QA-RA sign-off on the condition list (§b) and contraindications (§f).
- Formal paediatric-use review.
- Confirm classification via CDSCO's published risk-classification list or an MD Online portal
  query (per guidance §7.1), rather than relying solely on the Table 2 self-assessment in
  `docs/regulatory-foundation.md` §2.3.
