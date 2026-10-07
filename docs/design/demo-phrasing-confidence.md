# Demo phrasing vs classifier confidence

Measured 2026-09-29. This is the only measurement of the effect; nothing else in the repo quantifies it.

## Finding

The demo personas' 97 to 99% confidence is partly a product of keyword-shaped input. The same
clinical content phrased as a sentence, the way a worker types or dictates it, lowers the symptom
model's confidence, by up to 56 points on one persona. The top diagnosis did not change for any
persona; the confidence and the differential did.

Never present the demo confidence as evidence of field performance. The demo inputs are
vocabulary-matched to the classifier's training data. Same caveat class as the drishti_v2 metrics.

## Method

- Endpoint: `POST /api/v1/evaluate` on the local `samd-classifier` container (`localhost:8000`),
  `model_version` `toy-v0.6-observed-glucose-4tier`, which is the legacy TF-IDF + XGBoost path.
- Inputs: each persona in `DemoPatientProfile.kt`, once with the committed pipe-separated
  `mainConcern` (master) and once with a first-person sentence rewrite of the same clinical content.
  Age from date of birth at 2026-09-29, BMI from weight and height, every vitals field and random
  glucose from the persona. Only `symptom_string` differed between the two calls of a pair.
- Reported value: `original_symptom_confidence` of the primary ICD candidate, the raw symptom-model
  probability before the vitals-alignment adjustment.
- The sentence rewrite was reverted and is not committed. The committed text already is the
  canonical pipe-term form, so a rewrite in that direction is a revert, not a candidate.

## Result

| Persona | Primary (both) | Pipe terms | Sentence | Drop | Differential change |
|---|---|---|---|---|---|
| Obesity (diabetic picture) | E11 | 97.3% | 41.1% | 56.2 pts | M17 (knee osteoarthritis) becomes #2 at 17.8% |
| Hypertension | I10 | 99.8% | 88.5% | 11.3 pts | M17 becomes #2 at 8.9% |
| Pediatric UTI | N39.0 | 99.7% | 92.2% | 7.5 pts | A09 second in the differential at 2.2% |
| Dengue | A91 | 98.5% | 96.5% | 2.0 pts | E66 enters the differential |
| Typhoid | A01.0 | 99.5% | 97.6% | 1.9 pts | B54 becomes #2 at 0.5% |

Sample pair, Obesity persona:

- Pipe terms: `Excessive thirst | frequent urination | general weakness | fatigue | blurred vision`
- Sentence: `For the past few months I have excessive thirst, frequent urination day and night, blurred vision, and severe general weakness with fatigue.`

## Mechanism (from `classifier-wire-format-investigation.md` section 1)

Two TF-IDF vectorizers (word 1-2 grams, char_wb 3-5 grams) trained on 118 distinct lowercase
pipe-separated terms; no inference-time preprocessing. Filler words are out of vocabulary for the
word vectorizer but still contribute char n-grams, and they change the word bigrams around each
term, so the vector moves away from the trained region.

## Open items, inferences to verify (not fixes)

1. `/evaluate` is still the legacy TF-IDF model. E66 appears in a differential and E66 was dropped
   from the rebuilt label space, which is consistent with the new model's Android wiring still being
   open. Everything demoed today runs on the model being replaced.
2. `/evaluate` has no confidence gate. By trace, the 0.90 human-verification threshold
   (`HUMAN_VERIFICATION_CONFIDENCE_THRESHOLD`) covers `/assess` only. A 41% top diagnosis with an
   unrelated second option goes through ungated. Check whether that is intended and covered by a
   hazard row. Belongs with the classifier-reliability track; conformal abstention on the rebuilt
   model is the structural answer.
3. `fillDemoData` sets `captureMethod = DIGITAL_MONITOR` on hand-typed values carrying MANUAL
   provenance. The capture-method dropdown is the worker's own attestation (REQ-TRS-05) and is never
   auto-set. Recommendation: demo fill leaves it unset so the presenter picks it.
