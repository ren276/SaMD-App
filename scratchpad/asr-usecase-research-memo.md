# ASR Use-Case, Formatting Boundary, and Field-Reduction Research Memo (STEP 1, read-only)

Run date: 2026-08-30. Branch `master`, HEAD `a69101c`. Working tree: no tracked modifications
(`git diff` and `git diff --cached` both empty); untracked `dashboard.html` and `scratchpad/`
only. Nothing staged, committed, or pushed. Nothing written outside `scratchpad/`. `.env`,
`local.properties`, `BuildConfig` untouched.

Companion to `scratchpad/asr-field-audit-memo.md`, read in full and treated as ground truth for
this repo. This memo updates that memo's DECISION GATE; it does not replace it.

Also read: `CLAUDE.md`, `PROGRESS.md` (last four session entries, lines 3244 to 3429),
`docs/requirements/intended-use-statement.md`, `docs/quality/risk-management-file.md`,
`docs/regulatory-foundation.md`, `docs/quality/design-history-file.md`,
`docs/quality/qms-overview.md`, `docs/backend/api-contract.md` sections 5.2 to 5.4.

---

## 0. STOP-AND-REPORT, up front

Three findings contradict a premise in this brief. Per the brief's own instruction these are
reported rather than designed around. All three change what should be built.

### R-1 (contradicts the brief directly). Full-sentence narrative is probably WORSE kernel input, not better.

The brief says "Recommend whether cleaned verbatim sentences are BETTER kernel input than today's
terse mock (likely yes)". The repo evidence points the other way, and it is not weak evidence.

`docs/quality/design-history-file.md:45` records a direct read of the upstream training scripts:

> "checked `SaMDClassifier/train_model.py` and `train_symptom_classifier.py` directly: neither
> reads drug/brand/company columns; Classifier A trains on
> `age,sex_encoded,systolic_bp,diastolic_bp,bmi,heart_rate,spo2,glucose` to `tier`, Classifier B
> on `symptom_string` to `icd_candidate`"

So `symptom_string` is not a free-form context field that a model reads incidentally. It is
**Classifier B's entire input feature**. Whatever distribution it was trained on is the
distribution it works on. Three independent pieces of in-repo evidence say that distribution is
terse, delimiter-separated symptom terms:

1. The contract's own example, `docs/backend/api-contract.md:902`:
   `"symptom_string": "fever, body ache, dry cough"`.
2. Every demo persona's `mainConcern`, which is pipe-separated canonical terms, not prose.
   `app/src/main/java/com/example/samdapp/data/mock/DemoPatientProfile.kt:143`:
   `"Excessive thirst | frequent urination | general weakness | fatigue | blurred vision"`;
   `:211` and `:287` follow the same shape.
3. The response field is literally named `original_symptom_confidence`
   (`docs/backend/api-contract.md:928`), and the `why` string in the same example
   ("fever with dry cough and normal spo2") reads as term-level evidence, not sentence
   comprehension.

Feeding a paragraph of patient narrative into that field is a **train/serve distribution shift**
on a trained classifier. The likely failure is not a crash. It is a quiet accuracy drop with a
confidence number attached, which is the worst failure shape this app has (it is the exact
pattern the empty-differential fabrication fix and H-09 exist to prevent).

**What cannot be verified here, stated plainly:** the upstream `SaMDClassifier` service is not in
this repository. `backend/core/app/config.py:71` points at `kernel_base_url =
"http://10.16.4.182:8000"`, a LAN host. `symptom_model.json` and `symptom_model_meta.json` are
named in `app/src/main/java/com/example/samdapp/domain/model/TrainedIcdCandidate.kt:4-5` but live
elsewhere. I cannot read the vectorizer, the tokenizer, or the training corpus. So the correct
statement is: **the in-repo evidence points strongly to a terse-token expectation, and the
opposite assumption is unverified and must be measured against the real kernel before anything
ships.** Do not build "tidy the ramble and send it" on the assumption that narrative helps.

Consequence for the whole plan, and this is the memo's thesis: **"tidy alone" is not sufficient,
and it is not sufficient in the opposite direction from the one the brief assumed.** Tidy
preserves narrative form. Narrative form is the thing the kernel may not be able to consume.
The safe architecture is therefore to split the ramble in two: narrative stays narrative for a
human to read and never touches `symptom_string`, and the kernel keeps receiving terse
canonical terms that a **human taps from a fixed list**. Section 3.4 develops this.

### R-2 (new hazard, created by narrative itself). The backend PHI guard checks key names only, never values.

`backend/core/app/adapters/kernel/phi_guard.py:64-76`:

    def assert_no_identity_fields(payload: Mapping[str, Any]) -> None:
        present = sorted(DENYLIST & payload.keys())

`DENYLIST` (`:33-61`) is a frozenset of **field names**: `full_name`, `aadhaar_number`,
`mobile_number`, `village`, `pincode`, `date_of_birth` and so on. The check is a set intersection
against `payload.keys()`. It never inspects a single value.

That guard is correct and sufficient for today's input, because today `symptom_string` holds
`"fever, body ache, dry cough"` and a terse symptom list structurally cannot carry an identifier.
It stops being sufficient the moment the field holds a patient's own spoken words. A patient
saying "main Sunita hoon, mera number nau eight ..." puts a name and a phone number inside a
value that passes both the Pydantic `extra="forbid"` model and the denylist, and gets forwarded
verbatim to an off-host service at `10.16.4.182:8000`.

H-10's guarantee is real but is a **type-level** guarantee: `KernelPayload` has no `Patient`-typed
field, so a `Patient` object cannot reach the kernel. It was never a **value-level** guarantee,
and the module docstring at `phi_guard.py:1-22` is explicit that the two mechanisms are
"the request Pydantic models" plus "an explicit denylist" of names. Neither reads content.

This is a hazard that ASR narrative *creates*, and it belongs in the risk file alongside the
H-15 entry the prior memo proposed. It is a strong independent argument for R-1's conclusion:
if narrative never enters `symptom_string`, this hazard never opens.

### R-3 (updates the prior memo). The prior memo's "retain the verbatim transcript immutably" recommendation has a real counter-argument that must be resolved deliberately.

The prior memo recommended retaining the verbatim transcript as immutable source of truth. The
medico-legal literature on ambient scribes argues the opposite: retaining audio and transcripts
"can increase subpoena exposure and documentation discrepancies" and "grows the discoverable
footprint without necessarily improving the clinician's legal position"
([McDermott](https://www.mcdermottlaw.com/insights/all-about-ai-scribes-faqs-for-health-systems-and-providers/),
[ABA Health Law](https://www.americanbar.org/groups/health_law/news/2026/ambient-ai-scribes-privacy-cybersecurity/)),
and vendor practice varies, with some deleting audio within hours.

Section 1.4 resolves this rather than picking a side by reflex, and the resolution changes the
prior memo's wording. Short version: retain the **transcript** as encrypted clinical data (it is
already a persisted clinical field, `Consultation.transcription`), never retain the **audio**,
and keep the audit log carrying provenance only. The counter-argument is about audio and about
the legal-record boundary, not about a text field the app already stores.

---

## TASK 0 (BLOCKING): the off-backend recognizer check

### 0.1 The code, exactly as written

`app/src/main/java/com/example/samdapp/data/transcription/AndroidSpeechRecognizerService.kt:57-63`:

    val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
    }

Availability check at `:35` is `SpeechRecognizer.isRecognitionAvailable(context)`, the
any-recognizer variant.

Verified absent by grep across `app/src`: `EXTRA_PREFER_OFFLINE`,
`createOnDeviceSpeechRecognizer`, `isOnDeviceRecognitionAvailable`. Only two extras are set, and
neither constrains where processing happens.

`app/src/main/AndroidManifest.xml`: `INTERNET` granted (line 8), `RECORD_AUDIO` granted (line 5).
No `<queries>` element anywhere in the manifest. `app/build.gradle.kts:32-33`: `minSdk = 26`,
`targetSdk = 37`.

### 0.2 (a) Can it go off-device as written?

**Yes.** Two independent reasons, both from platform documentation rather than speculation:

1. `createSpeechRecognizer()` binds to whatever `RecognitionService` the device provides,
   typically Google's. That service chooses its own processing location.
2. `EXTRA_PREFER_OFFLINE` is documented as defaulting to false, and the documented meaning of
   the default is that "either network or offline recognition engines may be used"
   ([Android RecognizerIntent reference, EXTRA_PREFER_OFFLINE, added API 23](https://learn.microsoft.com/en-us/dotnet/api/android.speech.recognizerintent.extrapreferoffline?view=net-android-34.0)).
   The app never sets it. So the app has explicitly opted into "the recognizer may use the
   network", by omission.

Whether a *given* device actually uploads depends on whether Google's on-device recognition model
for the selected locale is installed. Note `EXTRA_LANGUAGE` is `Locale.getDefault().toString()`,
so on a device set to `en_IN` or a regional locale the on-device model is less likely to be
present than for `en_US`, which makes the cloud path *more* likely in exactly the deployment
this app targets. That is a worse-than-random default, not a neutral one.

Two further code facts sharpen this. The app has no `<queries>` element, and since Android 11
package-visibility filtering applies to resolving services in other apps; the observable symptom
would be `isRecognitionAvailable()` returning false and the feature silently failing rather than
uploading. So on a modern device this path may be **broken**, not merely leaky. Both outcomes are
bad and both point the same way. And `AndroidSpeechRecognizerService` keeps transcripts only in a
`ConcurrentHashMap` (`:33`, `:41-43`), so `transcribe()` fails after process death anyway.

### 0.3 (b) What the fix is

Three options, ranked, with the honest limits of each:

1. **Add `EXTRA_PREFER_OFFLINE = true`.** One line. **Not sufficient for a medical device.** The
   platform documentation states that "depending on the recognizer implementation, these values
   may have no effect". A control whose own specification says it may be ignored is not a risk
   control that can be written into `docs/quality/risk-management-file.md` with a straight face.
   Worth doing as immediate harm reduction. Not worth calling a fix.
2. **Switch to `createOnDeviceSpeechRecognizer()`.** Stronger: from Android 13 (API 33) it forces
   on-device recognition and fails if no compatible local engine exists
   ([SpeechRecognizer reference, added API 31](https://learn.microsoft.com/en-us/dotnet/api/android.speech.speechrecognizer.createondevicespeechrecognizer?view=net-android-34.0)).
   But it was added at **API 31** and this app's `minSdk = 26`, so five API levels of the
   supported range have no such API and would need a fallback, and the fallback is option 1,
   which is the thing that may have no effect. It also remains a third-party engine the app
   cannot version-pin, which the prior memo already flagged as unpinnable SOUP.
3. **Replace the platform recognizer with the in-process on-device engine (sherpa-onnx).**
   This is the actual fix. The recognition runs inside the app process against a model shipped in
   the APK. There is no `RecognitionService`, no IPC to another app, no vendor policy to trust,
   and the model is version-pinnable as SOUP with a file hash. It is the only option where "audio
   never leaves the device" is a property of the architecture rather than a request to someone
   else's code.

Recommendation: option 1 immediately as a stopgap **if** the feature is left enabled at all, and
option 3 as the fix. The cleanest interim posture is stronger than either: **disable the voice
affordance entirely until option 3 lands.** The prior memo's own principle applies, taken from
`UnavailableVitalsSource` and H-13: no affordance is better than a degraded one.

### 0.4 (c) Does this make PR 0 a privacy-severity item?

**Yes. PR 0 is reclassified from a UX/safety fix to a privacy and safety fix, and its severity
goes up.** Three reasons:

- It is a probable **DPDP** exposure. Patient clinical narrative is sensitive personal data, and
  the app may be transmitting it to a third-party processor with no data-processing agreement, no
  consent covering it, and no record of it. The clinical consent captured at
  `presentation/consent/` (REQ-TRS-01) covers care, not third-party speech processing.
- It **contradicts a locked architectural principle**, that all external calls route through the
  backend. H-11 covers the one sanctioned external call (Gemini brand lookup) and its control is
  explicitly that "only the generic drug name is sent, never patient identity/vitals/symptom
  text". Patient symptom text going to Google is precisely the thing H-11's control was written
  to exclude, and it is not covered by any hazard row today.
- H-04's posture (SQLCipher at rest, Keystore, day-scoped cache) is a data-minimisation argument
  that this path quietly undercuts: the app encrypts the narrative on disk and may be sending the
  same narrative in clear intent to a cloud recogniser.

The prior memo's DECISION GATE item 6 asked whether to check this. The answer from the code is
that the check should be treated as confirmatory, not exploratory: the default is documented,
the extra is absent, and `INTERNET` is granted. The burden is now on evidence that it does *not*
transmit, rather than on evidence that it does.

**Operator confirmation still required on the physical device**, because per-device behaviour
depends on the installed recognition service and the on-device model for the active locale. The
check is a device-side network capture (or Private DNS / a proxy) during one `Record main
concern` tap on a `Pixel_9_Pro_3` AVD or the physical Pixel, in airplane mode versus online. I
did not run it; I was instructed not to, and no emulator or network call was made by this session.

---

## TASK 1: how ASR-driven documentation actually works in real clinical settings

### 1.1 The OPD dictation pattern, and what actually makes it safe

The safety of clinician dictation does not come from the recogniser being accurate. The evidence
says the recogniser is not accurate. It comes from a **two-stage human curation gate**, and the
size of that gate's effect is measurable.

The cleanest measurement is Zhou et al., *JAMA Network Open* 2018;1(3):e180530, a cross-sectional
analysis of 217 dictated clinical notes (83 office notes, 75 discharge summaries, 59 operative
notes) from 144 physicians across 2 health systems using Dragon Medical
([PMC6203313](https://pmc.ncbi.nlm.nih.gov/articles/PMC6203313/)). Error rate by stage:

| Stage | Error rate | Notes containing at least one error |
|---|---|---|
| Raw speech-recognition output | **7.4%** | 96.3% |
| After professional transcriptionist review | **0.4%** | 58.1% |
| After physician review and signature | **0.3%** | 42.4% |

Error types: deletions 34.7%, insertions 27.0%, substitutions 18.2%. Clinically significant error
rates across the three stages were 5.7%, 8.9%, and 6.4%. The authors' own summary: "Seven in 100
words in SR-generated documents contain errors; many errors involve clinical information."

Read that table carefully, because it makes two opposite points at once.

**The gate works.** A twenty-fold reduction, 7.4% to 0.3%, is not marginal. That is the entire
safety case for clinical dictation, and it is human labour, not model quality.

**The gate leaks.** After a physician read and signed, **42.4% of notes still contained at least
one error**, and 6.4% of remaining errors were clinically significant. A trained, motivated,
professionally liable clinician reviewing their own dictation of their own patient does not catch
everything.

What makes the OPD clinician a good gate, specifically:
- They authored the content, so they hold the ground truth in working memory and are checking a
  transcript against their own intent, not reading a stranger's words cold.
- They are the salience authority. They decide what matters, and the transcript is raw material
  for a judgement they were going to make anyway.
- They sign. Attribution and liability are personal and immediate.
- They are reading in their own professional register, so a wrong clinical word is jarring to
  them in a way it is not to a lay reader.

### 1.2 Why that safety does not transfer to a frontline PHC worker

Every one of those four properties is absent or inverted in the app's actual user. Per
`docs/requirements/intended-use-statement.md` §d, the intended users are **"Non-clinical,
non-specialist users: ASHA worker, nurse, compounder"**, and the doctor is "a second,
asynchronous user ... not co-located with the frontline worker at the point of AI-output
generation". That sentence is already the swing factor in the unresolved B-versus-C
classification (`docs/quality/risk-management-file.md` §1). It is also exactly the sentence that
kills the dictation analogy:

- **The worker did not author the content.** The patient did. The worker is checking a machine's
  transcript of a third party's speech against nothing they hold in memory. There is no internal
  ground truth to compare against.
- **The worker is not the salience authority** and, by the app's own design, must not be. Deciding
  which part of a ramble is clinically relevant is a clinical judgement the intended-use statement
  deliberately does not assign to them.
- **The worker does not sign anything clinical.** The physician AGREE/MODIFY/REJECT gate is
  downstream and asynchronous, and by then the transcript has already become the field value the
  physician reads. The physician is reviewing a diagnosis, not auditing a transcription.
- **Register mismatch.** A wrong clinical word is not jarring to a lay reader, which is the entire
  problem. "No chest pain" transcribed as "chest pain" is fluent, grammatical, and plausible.

Field research on this exact user population supports the concern rather than dismissing it. A
mixed-methods usability study with 37 ASHA workers in Jabalpur and Mirzapur found "poor visibility
of the system, inconsistent data entry formats, inadequate error prevention mechanisms"
([Springer](https://link.springer.com/chapter/10.1007/978-981-96-5487-1_11)); a Telangana
qualitative study identifies "usability issues, language and literacy barriers ... and lack of
real-time technical support" as core digital challenges for community health workers
([ResearchGate summary](https://www.researchgate.net/publication/396020108_Exploring_the_Digital_Challenges_Faced_by_Community_Health_Workers_in_a_District_of_Telangana_India_A_Qualitative_Study)).
Reporting on India's digital health push documents that ASHAs maintain 10+ paper registers
alongside app entry, under time pressure
([New Lines](https://newlinesmag.com/reportage/indias-digital-health-push-is-overworking-its-front-line-women/),
[Data & Society](https://datasociety.net/points/from-care-labor-to-data-labor-indias-door-to-door-health-activists/)).

Time pressure plus low task-specific literacy plus a plausible-looking suggestion is the exact
input condition for rubber-stamping. Which is section 1.3.

### 1.3 The documented automation-bias and rubber-stamp failure

The failure is well evidenced across three separate settings.

**Front-end speech recognition with clinician-only review (closest analogue to this app).** Goss,
Zhou and Weiner, *International Journal of Medical Informatics* 2016, found **71% of emergency
department notes contained at least one error, and 14.8% of errors were judged clinically
significant** ([PubMed 27435949](https://pubmed.ncbi.nlm.nih.gov/27435949/)). Compare with Zhou
2018's 42.4% post-signature: removing the professional transcriptionist stage and leaving only a
busy clinician raises the rate substantially. The Joint Commission issued a safety alert on this
class of risk ([Quick Safety 12](https://www.jointcommission.org/resources/news-and-multimedia/newsletters/newsletters/quick-safety/quick-safety--issue-12-transcription-translates-to-patient-risk/transcription-translates-to-patient-risk/)).

**Generative ambient scribes.** A pragmatic prospective pilot of 7,545 notes from 31 physicians
(July to August 2024), with 356 notes formally evaluated, found **accidental omissions in 18%
(n=64), hallucinations in 11.5% (n=41), accidental inclusions in 9.3% (n=33)**, bias 1.1% (n=4);
94.7% (337/356) were free of significant errors, but the authors conclude that because a small
number carried risk of serious harm if uncorrected, "careful clinician review of notes remains
imperative" (*JMIR Medical Informatics*, 2026;
[medinform.jmir.org/2026/1/e86474](https://medinform.jmir.org/2026/1/e86474)). Note which error
type leads: **omission**. The most common failure of a generative summariser is dropping
something, and an omission is invisible to a reviewer who does not already know what was said.
That is the single strongest argument in this memo for retaining a verbatim transcript.

**Automation bias in clinical decision support generally.** UK general practitioners changed
prescriptions in response to CDSS advice in about 22.5% of cases, and **in 5.2% of all cases
switched from a correct to an incorrect prescription**
([PMC5356416](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5356416/)). In radiology, readers were
significantly influenced by AI-suggested BI-RADS categories and accuracy dropped markedly when
the AI was wrong, with less experienced readers most susceptible
([ScienceDirect](https://www.sciencedirect.com/science/article/pii/S138650562500440X)). Training
and seniority reduce but do not eliminate false agreement
([npj Digital Medicine](https://www.nature.com/articles/s41746-025-01503-7)).

The gradient in that last sentence is the whole point: susceptibility rises as expertise falls.
The app's frontline user is at the far end of that gradient.

This is not a new concern for this repo. `docs/quality/risk-management-file.md` H-02 already
records the same failure shape for the physician gate: "still no enforcement stopping a reviewer
from picking AGREE without actually reading the evidence (a UX/training issue, not a code gate)".
An ASR confirmation tap is structurally the same control with a less expert operator, and the
same open weakness applies to it from day one.

### 1.4 What ambient-scribe products actually do at the transcript-to-field step

Honest reporting of what could and could not be established from public sources.

**Established.** These products generate a note and place it into the EHR. Nabla "capture[s]
physician-patient conversations to create a transcript, then summarize[s] it in the form of
clinical notes, which are integrated into the EHR and automatically add the generated text to the
provider note", and is described as "transcriptional and does not provide clinical decision
support". Abridge is described as offering "real-time note generation with structured outputs
that are immediately usable in the EHR", and ambient scribes generally "output attested clinical
records with ICD-10 mapping". Augnito, the India-specific comparator, is a dictation product
"trained to specifically understand all Indian accents out of the box", with clinician edit,
format and finalise as the workflow
([Analytics India Mag](https://analyticsindiamag.com/ai-startups/indias-first-speech-recognition-system-for-healthcare-industry-the-startup-story-of-augnito/)).

**Not established, and this matters.** Public product documentation does **not** clearly establish
a mandatory human-confirmation step at the level of an individual discrete/structured field.
The pattern that is documented is generation into a note that a clinician signs, which is a
**document-level** gate, not a **field-level** one. I could not verify from public sources that
any of these products requires an affirmative per-field tap before a extracted value populates a
discrete EHR field. Stating that as an unknown rather than assuming either answer.

**Transcript retention varies by vendor, and the legal advice leans against retention.** "Some
vendors delete the audio within hours; others retain recordings and transcripts to retrain models
or to let clinicians replay a visit." Health-law commentary advises that scribes "should not
retain session recordings or full transcripts, with only the note you review and sign existing",
because retention "can increase subpoena exposure ... without necessarily improving the
clinician's legal position", and recommends a policy stating the audio and transcript "is not part
of the legal medical record ... just transitory communications in draft form"
([McDermott](https://www.mcdermottlaw.com/insights/all-about-ai-scribes-faqs-for-health-systems-and-providers/),
[ABA Health Law](https://www.americanbar.org/groups/health_law/news/2026/ambient-ai-scribes-privacy-cybersecurity/),
[McAfee & Taft](https://www.mcafeetaft.com/healthcarelinc-qa-the-risks-of-using-virtual-scribes-and-ambient-listening-for-documentation/)).

**Resolving R-3, the tension with the prior memo.** That advice is aimed at a different object and
a different regime. It concerns **audio recordings** and US discovery exposure for clinician-signed
notes. This app is a regulated SaMD under ISO 14971 and CDSCO where the auditability of a risk
control is itself an obligation, and the JMIR omission finding (18%, the leading error type) means
that without a verbatim source there is **no way for anyone, ever, to detect that something was
dropped**. Reconciliation, which supersedes the prior memo's wording:

- **Retain the transcript as clinical data**, in the encrypted Room database, in the field it
  already occupies (`Consultation.transcription`, already persisted, already synced, already
  covered by SQLCipher and the day-scoped cache under H-04). This is not new PHI exposure; the
  app already stores this exact field.
- **Do not retain the audio.** The prior memo did not ask for audio retention and should not.
  This is where the legal advice bites, and REQ-AIL-03 already sets the precedent: private ailment
  audio is app-private, never uploaded, delete-only, no playback path exists.
- **Keep the audit log free of both.** Provenance, slot name, model id and version, character
  count, edit distance. No content. This was already the prior memo's B.4 position and it stands.
- **Declare the boundary explicitly** in the eventual controlled doc: transcript is a clinical
  record, audio is transient and not retained, audit is provenance-only.

### 1.5 The explicit lesson for a rural PHC / ASHA setting

Stated as the sentence to quote back at any future session:

> Clinical dictation is safe because an expert who authored the content curates it before signing.
> A PHC worker transcribing a patient's speech has none of those properties: they did not author
> it, they cannot judge its clinical salience, they do not sign it, and they are under exactly the
> time pressure that produces rubber-stamping. Therefore the confirmation tap this app builds is a
> **weaker** control than the one that makes hospital dictation work, and it must be compensated
> for by narrowing what voice is allowed to touch, not by making the tap look more official.

Three concrete design consequences, all of which reinforce the prior memo:

1. **Narrow the surface.** Voice touches narrative fields a human reads. It never touches
   measurements, identifiers, or safety toggles. This is the prior memo's A.3 exclusion list and
   it should be treated as load-bearing rather than conservative.
2. **Make the tap cheap to do right and impossible to do by accident.** Suggestion beside the
   field, never in it; no timeout; no auto-accept; `canSend` false while a suggestion is
   outstanding. Already the prior memo's B.3.
3. **Instrument the gate and expect it to leak.** `editDistance` and confirm-versus-reject rates
   from the audit payload are the only way to find out whether workers are actually reading. If
   confirmations come back with a median edit distance of zero and a sub-second dwell time, the
   gate is being rubber-stamped and the feature should be pulled for that field. Design the
   measurement in from PR 3, not later.

---

## TASK 2: the formatting boundary

### 2.1 The principle in one line

**Form may be changed deterministically. Content and meaning may not be changed at all.**

Operationally, a transformation is allowed only if it satisfies **all five**:

1. **Deterministic.** Same input, same output, always. No sampling, no temperature, no learned
   weights.
2. **Auditable and reversible in principle.** The verbatim transcript is retained, so any
   transformation can be re-derived and inspected after the fact.
3. **Content-preserving.** No token carrying clinical meaning is added, removed, reordered across
   a clause boundary, or substituted.
4. **Salience-neutral.** It never decides what is important. No selection, no ranking, no
   dropping "irrelevant" content.
5. **Negation-inert.** It cannot alter, attach, detach, or scope a negation.

Rule 5 is separate from rule 3 on purpose. Negation is the highest-consequence single token class
in clinical text: "no chest pain" and "chest pain" differ by one short word, are both fluent, and
invert clinical meaning. It gets its own rule so no future session can argue it away as a subcase.

### 2.2 The ALLOWED ruleset, English-first, rule by rule

Each rule is versioned as a set (`tidy_rule_version`, mirroring
`derivation_rule_version = "HAN-07/08-v1"` in `app/domain/kernel_derivation.py`, the precedent
H-12 established for separating a rule change from a model change). Verdicts are SAFE, SAFE WITH
CONSTRAINT, or NOT SAFE.

| # | Rule | Verdict | Reasoning |
|---|---|---|---|
| T1 | Trim leading/trailing whitespace, collapse internal runs of whitespace to one space | **SAFE** | Pure whitespace. No token affected |
| T2 | Capitalise the first alphabetic character of the string | **SAFE** | Display-only. Note it must be first-character-only, not per-sentence, because per-sentence needs T6 |
| T3 | Strip a trailing filler token from a closed, versioned list (`um`, `uh`, `er`, `hmm`, `haan`, `matlab`) when it stands alone as a whole token | **SAFE WITH CONSTRAINT** | Constraint: closed list, whole-token match, case-insensitive, no substring matching, no stemming. `haan` and `matlab` are code-mixed fillers, but see the warning below |
| T4 | Normalise spoken cardinal numbers to digits ("three" to "3") | **SAFE WITH CONSTRAINT** | **Strong constraint.** Only in a narrative field, only for standalone cardinals, and it must **never** attach a unit or produce a measurement-shaped token. See 2.3 |
| T5 | Normalise obvious duration phrases to a canonical form ("three days" to "3 days") | **SAFE WITH CONSTRAINT** | Same constraint as T4 plus: the unit must already be present verbatim in the source. Never infer a unit |
| T6 | Sentence segmentation and terminal punctuation insertion | **NOT SAFE as automatic** | See 2.3. This is the rule most people assume is trivial and it is the one that can silently rescope a negation |
| T7 | Remove speaker-attribution prefixes ("the patient says", "she says that") | **NOT SAFE** | See 2.3 |
| T8 | De-duplicate repeated phrases | **NOT SAFE** | See 2.3 |
| T9 | Expand contractions ("doesn't" to "does not") | **SAFE WITH CONSTRAINT** | Closed list, and the negative contractions must expand to the negative form. Mechanically simple, but it touches negation, so it needs a test per entry |
| T10 | Lowercase everything, or Title Case everything | **NOT SAFE** | Destroys proper nouns and abbreviation casing; "MI" versus "mi" |
| T11 | Spell correction / autocorrect | **NOT SAFE** | Non-deterministic in practice, and a "correction" of a drug or condition name is a content substitution. This is the Eka Care drug-name example verbatim (2.5) |
| T12 | Strip punctuation the ASR emitted | **SAFE WITH CONSTRAINT** | Only if it does not merge clauses. Removing a comma can rescope a modifier |
| T13 | Truncate to a maximum length | **NOT SAFE** | Truncation is silent omission, which is the JMIR study's leading error type |
| T14 | Unicode normalisation (NFC), strip zero-width and control characters | **SAFE** | Encoding hygiene, not content |

### 2.3 The four rules that look safe and are not, explained

These four deserve their own space because they are the ones a future session will be tempted by.

**T6, sentence segmentation. NOT SAFE as automatic.**
The brief asks directly whether this is safe. It is not, and the reason is specific rather than
general caution. Inserting a full stop is asserting a clause boundary, and a clause boundary
**scopes negation**. Take the ungrammatical, unpunctuated run-on that real speech produces:

    no fever since two days cough is there

Segment it one way and you get "No fever. Since two days cough is there." Segment it another and
you get "No. Fever since two days, cough is there." The two differ on whether the patient has a
fever. Nothing downstream can tell which one was meant, and the second reads perfectly fluently.
A rule that can invert a symptom's presence is not a formatting rule.
Allowed alternative: **display** segmentation, where the UI renders line breaks at pause
boundaries the ASR itself reported, without writing punctuation into the stored value. Form
changes on screen, stored content does not.

**T7, removing "the patient says". NOT SAFE.**
The brief asks this one specifically too. Attribution is clinical content, not noise. "The patient
says she has no pain" and "she has no pain" are different clinical claims: the first is reported
symptom, the second reads as an examination finding. In a workflow whose entire safety argument
rests on separating what a non-clinical worker recorded from what a clinician determined, erasing
the reporting frame erases exactly the distinction the intended-use statement depends on. Also,
matching "says that" requires matching a variable-length span, which is parsing, not tidying.

**T8, de-duplicating repeated phrases. NOT SAFE.**
Repetition in patient speech is clinical signal. A patient who says "chest pain, the chest pain,
it is bad, chest pain" is expressing emphasis and distress, and repetition is one of the few
salience signals present in raw narrative. De-duplication is a salience judgement (rule 4) wearing
a formatting costume. It is also the mechanism by which a summariser starts dropping things,
which is the 18% omission rate in the JMIR study.

**T4/T5, spoken-number normalisation. SAFE only inside a hard constraint.**
The brief lists this as allowed and it can be, but only with a constraint the brief does not
state. If narrative tidy converts "one forty over ninety" into "140/90", it has manufactured a
**measurement-shaped token** inside a narrative field. That token then sits in text that a human
reads as if it were a recorded vital, and if it ever reaches `symptom_string` it sits next to real
vitals in a model input. The prior memo's A.3 exclusion list exists precisely to stop measurements
from arriving via voice; a number-normalisation rule would smuggle them in through the back door
of a field that was declared safe.
Constraint, stated as a rule: **T4/T5 apply only to a standalone cardinal or a cardinal plus a
time unit that is verbatim in the source. They must never produce a value-plus-clinical-unit pair
(`mmHg`, `bpm`, `%`, `mg/dL`, `°C`, `/`-separated pairs). If the transformation would produce such
a pair, the rule does not fire and the source text is left alone.** And the numeric-only rejection
from the prior memo's A.2 stays: a narrative field whose whole confirmed value parses as a number
is rejected, not accepted.

### 2.4 FORBIDDEN AS AUTOMATIC, and why each is a device function

Five operations, each forbidden as an automatic step, with the regulatory reason rather than a
gesture at one.

**F1. Extraction/routing of a ramble into structured slots.**
Deciding that "since two days I have burning when I pass urine" fills `chiefComplaint` rather than
`relevantHistory`, and that its duration is "2 days", is **interpretation of clinical narrative**.
Under `docs/requirements/intended-use-statement.md` §g the app's device function is defined by
what feeds `KernelPayload` and what the outputs drive; an automatic router decides what the model
sees. Under IEC 62304 it is new software of a safety class inherited from the worst thing it can
cause, and what it can cause is a wrong model input with no human in between. Under ISO 14971 it
introduces a hazard with no risk control, because there is no point at which a human compares the
routing against the source.

**F2. Negation resolution.**
Determining that "no" in "no fever, cough since Monday" scopes over `fever` only is a clinical NLP
task with a documented error rate and no calibrated uncertainty. If it is wrong, the record says
the patient has or does not have a symptom they do not or do have. This is the single clearest
"drive clinical management" function in the list.

**F3. Salience selection (deciding what is clinically relevant).**
This is the definition of triage judgement. `intended-use-statement.md` §d assigns the users as
non-clinical and §a assigns "screening/triage" to the device with mandatory doctor review. A
component that silently decides which parts of the patient's account are worth keeping is
performing triage without review, and it does so **before** the AGREE/MODIFY/REJECT gate can see
what was discarded. A reviewer cannot reject content they were never shown.

**F4. Summarisation or any model-authored rewrite.**
This is the operation with the measured failure rate: 18% omissions, 11.5% hallucinations, 9.3%
accidental inclusions (JMIR 2026). It also directly breaches the locked wall that no generative
model sits in the decision path, because a rewritten symptom description **is** the decision-path
input via `symptom_string`.

**F5. Auto-applying any of F1 through F4 without an affirmative tap.**
Even a correct extractor, auto-applied, converts the app from "records what a human confirmed"
to "records what a model decided". That is the difference between a documentation aid and an
interpretation function, and it is the sentence on which the classification argument in
`docs/regulatory-foundation.md` §2.3 rests.

**Why this specifically breaks the Class B argument.** The classification in
`docs/quality/risk-management-file.md` §1 is "B or C, genuinely unresolved", with the swing factor
being whether the non-clinical-user note escalates Serious to Critical because the doctor's review
is asynchronous. The counter-argument that keeps it at B is that a human is meaningfully in the
loop at every step. **Auto-applied extraction removes the human from the step where the model's
interpretation becomes the record.** It does not merely weaken the B argument; it removes the
premise the B argument is built on, and it does so at exactly the point the guidance says is
decisive. The honest reading is that auto-applied extraction moves this app to C and does so
without anyone deciding to.

### 2.5 Why spell correction deserves its own mention

T11 is listed NOT SAFE and the Indian-healthcare evidence makes the point concretely. Eka Care's
worked example: reference `स्टारक्लेव 625mg`, ASR output `स्टारक्लाव छह सौ पच्चीस एमजी`. Traditional
WER scores it 50%, semantic WER 8.3%, but keyword error rate 33%, "appropriately flagging the
स्टारक्लेव versus स्टारक्लाव drug name substitution as critical"
([Eka Care](https://www.eka.care/services/beyond-traditional-wer-the-critical-need-for-semantic-wer-in-asr-for-indian-healthcare)).
A one-character drug-name substitution is exactly what a spell corrector produces confidently. It
is a content substitution in the highest-consequence token class, dressed as a formatting fix.

### 2.6 The only safe way to offer extraction later

If and when extraction is wanted, and section 3.4 argues something better should be tried first,
these six conditions are all mandatory. Any one missing and it is F1, not extraction.

1. **Constrained decoding into a fixed slot schema.** The model may only emit tokens that are
   valid for the target slot. It cannot author free text into a clinical field. For an enumerated
   slot the decode is restricted to the enum's members; for a free-text slot it may only emit a
   **contiguous span of the verbatim transcript**, never new words.
2. **Every suggestion lands in `VOICE_UNCONFIRMED`** and is refused by the repository write path
   (prior memo B.2). Nothing persists, nothing syncs, nothing reaches `KernelPayload`.
3. **One affirmative tap per slot.** Not one tap for a whole extracted form. A single "accept all"
   is a rubber stamp with extra steps, and section 1.3 is the evidence.
4. **The verbatim transcript is retained and displayed alongside every suggestion**, with the
   source span highlighted. Without this, an omission (the leading error type) is undetectable by
   construction.
5. **Every accept, edit, and reject emits an audit breadcrumb** carrying provenance, slot, model
   id and version, and edit distance. Never content.
6. **The model is parsing, never authoring.** Operational test, stated so it can be checked in
   review: *if the suggested value contains any token that does not appear in the verbatim
   transcript, and the slot is not an enumerated one, the system is authoring and the design is
   wrong.*

### 2.7 The quotable boundary, short form

> Tidy changes how text looks. Extraction changes what text means. The first may be automatic if
> it is deterministic, versioned, content-preserving, salience-neutral and negation-inert. The
> second may never be automatic, and may exist only as a constrained-decoding suggestion that a
> human accepts one slot at a time against a retained verbatim source.

Full statement for pinning is at the end of this memo.

---

## TASK 3: what the kernel actually needs as symptom input

### 3.1 How `symptomString` is built today

`app/src/main/java/com/example/samdapp/data/remote/RetrofitEvaluateSource.kt:45-48`:

    val symptomString = listOfNotNull(
        payload.chiefComplaint.takeIf { it.isNotBlank() },
        payload.transcription?.takeIf { it.isNotBlank() },
    ).joinToString(". ")

Two values, period-joined. `chiefComplaint` is a free-text field the worker typed or (today,
ungated) dictated. `transcription` is the ASR output written by `TranscribeAudioUseCase`.

Note the shape mismatch already present: the demo data puts **pipe-separated** terms inside
`chiefComplaint` (`DemoPatientProfile.kt:143`), and this code then **period-joins** that with a
second blob. So today's wire value is already a mix of two delimiter conventions.

### 3.2 How it is consumed backend-side

`backend/core/app/services/kernel.py:479-522`, function `evaluate`. The body is:

- resolve the case record,
- swap `case_token` for a pseudonym (`case_token_for`),
- `_forward(...)` to the upstream kernel,
- store the response verbatim in `evaluate_reports`,
- return it with the real case token restored.

`_forward` (`:164-230`) does a PHI guard, a circuit-breaker check, and an HTTP call. Schema
validation is `KernelEvaluateRequest` in `backend/core/app/schemas/kernel.py:35-56`, where
`symptom_string: str` with no length bound, no pattern, no normalisation.

**The backend is a pure proxy for this field.** It does not tokenize, split, keyword-match,
truncate, or inspect `symptom_string` in any way. `docs/backend/api-contract.md:915-917` says the
response is "passed through verbatim, mixed casing and all. The backend does not reshape, rename,
or re-key any part of it", and the request side is equally hands-off.

So the entire question is about the upstream `SaMDClassifier` service, which is **not in this
repository**.

### 3.3 Does the evaluate leg expect terse tokens? The evidence, and its limit

**Evidence for terse tokens, four independent items:**

1. `docs/quality/design-history-file.md:45`, from a direct read of the training code:
   "Classifier B [trains] on `symptom_string` to `icd_candidate`". `symptom_string` is the model's
   input feature, so its training distribution is binding.
2. `docs/backend/api-contract.md:902`, the contract's own example:
   `"symptom_string": "fever, body ache, dry cough"`.
3. `DemoPatientProfile.kt:143`, `:211`, `:287`: every persona's `mainConcern` is a
   pipe-delimited list of canonical clinical terms, never a sentence.
4. `docs/backend/api-contract.md:928`: the response carries `original_symptom_confidence`, and
   `"why": "fever with dry cough and normal spo2"`. Both read as term-level evidence.

**Evidence for narrative tolerance:** none found in this repository.

**The limit, stated plainly.** I cannot read `symptom_model.json`, `symptom_model_meta.json`,
`train_symptom_classifier.py`, or the vectorizer. They are in the SaMDClassifier repo, and
`backend/core/app/config.py:71` points at a LAN host (`http://10.16.4.182:8000`). I am not
asserting the model uses bag-of-words or a fixed symptom vocabulary; I am asserting that four
in-repo artifacts are consistent with a terse-token training distribution and none is consistent
with a narrative one.

**Is any tokenization assumption baked in that full sentences would break?** Not on the device and
not in `backend/core`. Both are format-agnostic pass-throughs. The risk is entirely
distributional, at the model, and it is invisible to every layer this repo controls. That is the
worst place for it to be, because nothing in this codebase will fail, log, or warn. The call will
succeed, return a differential with a confidence, and be stamped `REAL_INFERENCE`.

### 3.4 Recommendation, which contradicts the brief

**Do not send cleaned verbatim sentences to `symptom_string` on the assumption they are better.**
Send them only if measured to be at least as good against the real kernel, and treat that
measurement as a prerequisite, not a follow-up.

The recommended architecture instead, and this is the substantive proposal of this memo:

**Split the ramble at the field boundary, not with a model.**

- **The narrative stays narrative, and stays off the wire.** The tidied verbatim transcript lands
  in a narrative field a human reads: `Consultation.relevantHistory`, or better, a field that
  `KernelPayload`'s KDoc already excludes by construction
  (`aggravatingFactors`, `relievingFactors`, `impactOnDailyActivities`). The doctor reads it on
  `PatientSummaryScreen` and in the report (`ReportCanvasRenderer` already renders the verbatim
  chief complaint per REQ-RPT-01). It is genuinely useful there, and it is off every model path.
- **The kernel keeps getting terse canonical terms, chosen by a human tap.** `chiefComplaint`
  becomes a **multi-select over a fixed symptom vocabulary**, rendered as chips, with the
  transcript displayed beside it so the worker can see what the patient said while tapping. The
  wire value is then the pipe-joined selected terms, which is byte-compatible with the format the
  demo data and the contract example already use.

Why this is the right answer rather than a cautious one:

- It is **strictly better for the model** than either option the brief considered. Today's typed
  free text already lets a worker write anything into `symptom_string`; a fixed vocabulary makes
  the wire value more in-distribution than it is now, not less.
- It is **the biggest typing reduction available for that field**, which is the doctor's actual
  complaint. Tapping four chips beats typing a sentence, and it beats dictating-then-reading-then-
  confirming a sentence too.
- It **eliminates R-2 entirely.** A pick-list value cannot carry a patient's name or phone number,
  so the value-blind PHI guard stays sufficient and no new backend work is needed.
- It **eliminates F1 through F5 for the highest-risk field.** No model routes anything. The human
  does the mapping from narrative to terms, which is the one job the confirmation gate is actually
  suited to: recognition against a visible list, not verification of a generated string.
- It is **the repo's own established pattern**, twice. `TRAINED_ICD_CANDIDATES`
  (`domain/model/TrainedIcdCandidate.kt`) exists because free text was found to be unreimportable
  and out-of-class; H-02 records that "MODIFY requires a corrected diagnosis from a fixed 18-class
  list, not free text, closing one path for an unreviewable/untrainable correction to enter the
  loop silently". The identical argument applies to the model's *input* side and has simply never
  been made there.

**What the backend needs if narrative is sent anyway** (that is, if the operator overrides this
recommendation). Three things, and none is optional:

1. **A value-level PHI check on `symptom_string`**, closing R-2. Names, phone-shaped digit runs,
   12-digit and 14-digit sequences. This is new backend work with its own false-positive problem
   (blocking a legitimate clinical call because a symptom description contained a number is its
   own hazard), which is a further reason to prefer 3.4's architecture.
2. **A length bound** on `symptom_string` in `KernelEvaluateRequest`
   (`backend/core/app/schemas/kernel.py:43`), currently unbounded. Not truncation on the device
   (T13, NOT SAFE), but an explicit rejection so an oversized input fails loudly.
3. **A measured accuracy comparison against the real kernel**, terse versus narrative, on the same
   cases, before either format is chosen. Without it, changing this field's format is an
   unvalidated change to a trained model's input, which under `docs/quality/qms-overview.md:45` is
   the kind of change the Algorithm Change Protocol exists to govern.

### 3.5 So: is tidy alone sufficient?

**For the narrative fields, yes.** Tidy plus the confirmation gate is exactly right, and no
extraction is needed, because nothing there reaches a model.

**For `symptom_string`, no, and not because tidy is too weak.** Tidy is beside the point: the
problem is that narrative is the wrong shape for that field, and no amount of tidying changes its
shape. The answer is human-tapped terms, not better tidying and not automatic extraction.

---

## TASK 4: non-ASR field reduction, quantified

### 4.1 Method and its honesty

Keystroke estimates below are counted from the field definitions and demo data in this repo, with
a plausible field value assumed for each. They are **order-of-magnitude planning figures, not
measurements**. No timing study, no keystroke logging, and no worker observation exists in this
repo, and inventing precision would be worse than admitting the range. Structured taps are counted
separately because a tap is roughly one twentieth the effort and error rate of a character.

Registration is once per patient; the encounter screens are once per encounter, so per-visit
savings differ from per-new-patient savings. Both are given.

### 4.2 Register screen (once per new patient)

| Field | Today | Verdict | Keystrokes saved |
|---|---|---|---|
| Aadhaar (`RegisterScreen.kt:101`) | 12 digits typed | OCR-OR-SCAN | ~12 |
| ABHA (`:102`) | 14 digits typed | OCR-OR-SCAN | ~14 |
| Village (`:94`) | ~10 chars | PICK-LIST | ~10 |
| Block (`:95`) | ~10 chars | PICK-LIST | ~10 |
| District (`:96`) | ~10 chars | PICK-LIST | ~10 |
| Pincode (`:97`) | 6 digits | DERIVE from village | ~6 |
| State (`:183`) | dropdown, 33 options | DEFAULT to PHC state | 1 interaction |
| Guardian/spouse (`:89`) | ~15 chars | CARRY-FORWARD | ~15 on repeat visits |
| Emergency contact (`:90`) | 10 digits | CARRY-FORWARD | ~10 on repeat |
| Blood group (`:210`) | dropdown | CARRY-FORWARD | 1 interaction on repeat |
| Primary care clinic (`:103`) | ~15 chars | DEFAULT | ~15 |
| Referring physician (`:104`) | ~15 chars | PICK-LIST | ~15 |
| Full name (`:86`) | ~15 chars | KEEP | 0 |
| Age (`:87`) | 3 digits | DERIVE from DOB when present | ~3 |

**Register total: roughly 120 to 145 keystrokes eliminated per new patient**, of which about 26
(Aadhaar plus ABHA) come from scanning alone. Voice contributes **zero** here, correctly: every
single one of these is either a digit string, a bounded catchment list, or a value that does not
change between visits.

### 4.3 Medical background screen (once per patient, occasionally amended)

Six social-history free-text fields (`MedicalBackgroundScreen.kt:321-326`) at roughly 10 to 20
characters each: tobacco, alcohol, drugs, occupation, exposure, travel. Four medication fields
(`:243-245` plus kind), history description and year (`:203-213`), allergen and reaction
(`:272-273`), family condition and relation (`:294-295`).

Four of the six social fields (tobacco, alcohol, drugs, occupation) are closed sets wearing
free-text clothing. Medication name, dosage and frequency are all enumerable, and NLEM is already
the app's drug vocabulary.

**Medical background total: roughly 130 to 190 keystrokes eliminated per patient.** Voice's
honest share here is **two fields** (environmental exposure, recent travel), roughly 25 to 40
keystrokes, once per patient lifetime.

### 4.4 Compounder screen (every encounter)

| Field | Today | Verdict | Saved |
|---|---|---|---|
| Ailment duration (`CompounderScreen.kt:306-311`) | ~8 chars | PICK-LIST, reuse `DURATION_BUCKETS` | ~8 per ailment |
| Ailment onset (`:312-317`) | ~8 chars | PICK-LIST | ~8 per ailment |
| Measured unit (`:292-297`) | ~3 chars | PICK-LIST per ailment type | ~3 per ailment |
| Severity (`:299-305`) | 1-2 digits | Slider (Consultation already has one) | ~2 per ailment |
| Urinalysis (`:199`) | ~15 chars | PICK-LIST grid | ~15 when used |
| Pain score (`:178`) | 1-2 digits | Slider | ~2 |
| Height (`:172`) | ~3 chars | CARRY-FORWARD for adults | ~3 |

At 2 ailments per encounter (typical), **roughly 45 to 60 keystrokes per encounter**, plus ~15
when point-of-care tests are used.

Voice's share on this screen: ailment description and qualifiers, roughly **50 to 90 keystrokes
per encounter** at 2 ailments. This is genuinely the screen where voice competes.

### 4.5 Consultation screen (every encounter)

| Field | Today | Verdict | Saved |
|---|---|---|---|
| Symptom onset (`ConsultationScreen.kt:158`) | ~10 chars | PICK-LIST | ~10 |
| Main concern (`:148-153`) | ~40 chars | Multi-select over symptom vocabulary (3.4) | ~40 |
| Aggravating factors (`:179`) | ~20 chars | VOICE | ~20 |
| Relieving factors (`:180`) | ~20 chars | VOICE | ~20 |
| Impact on daily activities (`:181`) | ~30 chars | VOICE | ~30 |
| Other relevant history (`:182`) | ~30 chars | VOICE | ~30 |

Structured share: ~50. Voice share: ~100.

### 4.6 The comparison, per encounter and per new patient

| Track | Per repeat-visit encounter | Per new patient (registration plus background plus encounter) |
|---|---|---|
| **Non-ASR structured capture** | **~95 to 110** keystrokes | **~350 to 445** keystrokes |
| **ASR (narrative fields only)** | **~150 to 190** keystrokes | **~175 to 230** keystrokes |

Read that honestly, because it does not say what a "pick-lists win" conclusion would want it to
say. **Per encounter, voice saves more raw keystrokes than structured capture does.** That is the
true finding and it should not be buried.

But keystrokes are the wrong sole unit, for four reasons that all point the same way:

1. **The non-ASR track ships now.** Every item in it is a Compose component that already exists in
   this codebase: `DropdownField`, `FilterChip`, `Slider`, and the `DURATION_BUCKETS` pattern. It
   needs no model, no new SOUP, no ACP question, no risk-file entry, no confirmation-gate design,
   no dataset, no accuracy gate. The ASR track needs all of those and PR 0 first.
2. **The non-ASR track carries no new hazard.** The ASR track opens H-15, opens R-2 if narrative
   reaches the wire, and carries the rubber-stamp weakness of section 1.2 permanently.
3. **The ASR saving is not the net saving.** Voice replaces typing with speaking plus *reading a
   suggestion and deciding*. Section 1.1's evidence says that reading step is real work that gets
   skipped under time pressure. A pick-list tap has no verification step because the worker chose
   the value; there is nothing to verify.
4. **Structured capture improves data quality; voice at best preserves it.** Six free-text social
   fields today mean tobacco use is recorded as "yes", "smokes", "10/day", "current", and "haan"
   in five different records. That is a downstream analysis defect and a fixable one. Voice into
   the same free-text field produces the same mess, with an ASR error rate on top.

### 4.7 Recommendation on sequencing

**Pull the structured-capture work forward. Run it ahead of the ASR track, not merely parallel to
it.** Concretely, the prior memo's PR 7 moves to run alongside PR 0 and PR 1, and it splits:

- **PR 7a (first, highest value per unit of effort): Consultation and Compounder structured
  capture.** Duration and onset pick-lists on Compounder reusing the Consultation
  `DURATION_BUCKETS`, severity and pain sliders, measured-unit pick-list, urinalysis grid,
  symptom-onset pick-list. Roughly 95 to 110 keystrokes per encounter, every encounter, on
  components that already exist.
- **PR 7b: registration scan and pick-lists.** Aadhaar/ABHA scan, village/block/district
  catchment lists with pincode derived, state and clinic defaults. ~120 to 145 per new patient,
  and the two scan fields are the worst single keystroke burden in the app.
- **PR 7c: medical-background pick-lists.** Social history, medication, allergy, family history.
  ~130 to 190 per patient and the largest data-quality win.

The case, grounded in the doctor's actual words. The feedback was "PHC workers fill too many
textual fields". **Fields, not keystrokes.** The non-ASR track removes about 30 text fields from
existence. The ASR track removes about 4 to 6 fields' worth of typing while keeping every field
on screen and adding a verification step to each. If the doctor watched a worker use the app after
PR 7a to 7c, they would see a form that is mostly taps. After the ASR track alone, they would see
the same wall of text boxes with microphones next to some of them. The first is a direct answer
to what was said; the second is an answer to a different question.

This is not an argument against ASR. Ranks 1 to 4 of the prior memo's A.2 are genuine narrative
that structured capture cannot reach, and PR 5 delivers them. It is an argument about order.

### 4.8 Registration fields: scan and pick-list beat voice, explicitly

Evaluating "ASR on registration" honestly, as the brief asks, rather than assuming it:

| Field | Voice? | Better option | Why voice is worse |
|---|---|---|---|
| Aadhaar, 12 digits | **No** | QR/barcode scan | A single mis-transcribed digit is unverifiable, silent, and creates a wrong-patient link (H-03). Digit-string ASR errors do not look like errors |
| ABHA, 14 digits | **No** | Scan, or autofill from the existing `presentation/abha/` flow | Same, worse, 14 digits |
| Mobile, emergency contact | **No** | Carry-forward, or scan | Same class |
| Pincode | **No** | Derive from village | Same class, and derivable for free |
| Village, block, district | **No** | Catchment pick-list | Proper nouns are the worst ASR class for a non-native-trained model. A bounded list of the PHC's own villages is both faster and exactly correct |
| Full name | **No** | Typed, or ABHA autofill | Proper noun, wrong-patient hazard, and a name is the one thing a worker will notice is wrong if they typed it |
| Age, biological sex | **No** | Already 3 keystrokes / one chip | Both reach both classifiers as typed numerics (prior memo A.3) |
| State, category, marital status, blood group | **No** | Already dropdowns | Already one tap; voice cannot beat one tap |

**Conclusion: zero registration fields are voice candidates.** Every field on that screen is a
digit string, a proper noun, a bounded list, or already one tap. Registration is the clearest case
in the app where the structured track wins outright and voice should not be attempted at all.

---

## TASK 5: realistic input for the dataset and eval track

### 5.1 Mock data versus reality, from this repo

**What the app assumes today**, `DemoPatientProfile.kt:143`:

    mainConcern = "Excessive thirst | frequent urination | general weakness | fatigue | blurred vision"

Canonical clinical terms, English, pipe-delimited, no negation, no filler, no irrelevance, no
numbers, no code-mixing, and already mapped to a diagnosis. The api-contract example
(`api-contract.md:902`) is the same shape.

**What a real patient says.** A plausible, synthetic, non-PHI utterance in the same clinical
scenario:

    "matlab doctor sahab, do teen mahine se bahut pyaas lagti hai, paani peeta rehta hoon,
    raat mein teen chaar baar uthna padta hai bathroom ke liye, aur kamzori bhi bahut hai,
    kaam nahi hota, bukhar nahi hai, aur dekhne mein thoda dhundhla lagta hai, mere bhai
    ko bhi sugar hai"

Every property that makes this hard is present and none of them is in the mock:

- **Sentence-length and rambling**, not terms.
- **Code-mixed** Hindi and English throughout.
- **Filler and discourse markers**: "matlab", "doctor sahab".
- **A negation carrying real clinical weight**: "bukhar nahi hai" (no fever). It rules out a
  differential.
- **Spoken numbers inside narrative**: "do teen mahine", "teen chaar baar".
- **Irrelevant-to-slot content that is relevant elsewhere**: the brother's diabetes belongs in
  family history, not chief complaint. An automatic router would have to decide that. A human
  reading the transcript beside a pick-list simply would not tick it as a symptom.
- **Vagueness**: "do teen mahine" is a range, not a duration bucket.

The gap between the two is the whole problem, and it is a gap in **shape**, not just accuracy.
This is the same conclusion Task 3 reached from the model side.

### 5.2 What the eval test set must contain

Six mandatory categories. A test set missing any of them will pass a model that fails in the
field.

1. **Full-sentence and run-on narrative.** Multi-clause, unpunctuated, 15 to 60 seconds. The
   thing the mock has zero examples of.
2. **Ramble with slot-irrelevant content.** Utterances containing family history, social history,
   and pure conversation mixed into a chief-complaint answer. Grades whether the system correctly
   does *not* route it (and, under 3.4's architecture, whether the human does not tick it).
3. **Negation, as its own graded subset.** Explicit ("no fever"), code-mixed ("bukhar nahi hai"),
   scope-ambiguous ("no fever or cough since Monday"), and double ("not without pain"). This
   subset gets a 100% gate; see 5.4.
4. **Spoken numbers dictated into narrative.** Durations ("do teen mahine", "since three days"),
   frequencies ("teen chaar baar"), and critically **measurement-shaped utterances**
   ("BP one forty over ninety", "sugar two hundred") that must NOT normalise into a
   measurement-shaped token in a narrative field (rule T4/T5's constraint, section 2.3). This is
   a negative-test category and it is the one that proves the A.3 exclusion works.
5. **Code-mixed Hinglish and regional-language mixing.** ASR models show a relative WER increase
   of **30 to 50%** on code-switched speech versus monolingual input
   ([arXiv 2203.16578](https://arxiv.org/pdf/2203.16578)), so an English-only test set will
   overstate real accuracy by roughly that margin. Include it from day one to measure the
   English base model's degradation, even though the MVP does not target it.
6. **Channel and accent realism.** Mid-range Android microphone, PHC ambient noise, a second
   speaker in the background. Indian accent diversity across the deployment region's language
   backgrounds, not "Indian English" as one bucket. Two existing public benchmarks are the right
   reference points for construction method, not as substitutes for a domain set:
   [Svarah](https://www.academia.edu/111680794/Svarah_Evaluating_English_ASR_Systems_on_Indian_Accents)
   (9.6 hours, 117 speakers, 65 locations, Indian-accented English) and
   [LAHAJA](https://arxiv.org/pdf/2408.11440) (multi-accent Hindi). Reported healthcare
   voice-recognition findings also note that "dialectal variation and rural speech patterns
   increased WER by up to 15%" and that misrecognition of medical terminology accounted for 18% of
   semantic errors
   ([IJRSML](https://ijrsml.org/voice-recognition-accuracy-across-indian-languages-in-healthcare-chatbots/));
   both effects apply directly to this deployment.

### 5.3 Sourcing: no PHI, synthetic first

Unchanged from the prior memo's D.2 and reaffirmed:

- **No real patient audio, no exceptions.** The repo's proof standard is
  `backend/core/tests/test_abha.py::test_d5_no_phi_in_persisted_row_or_logs`, and the 2026-08-28
  live-run entry in `PROGRESS.md` where structure-only instrumentation was added, verified to
  contain zero patient values, and then removed so the file was byte-identical.
- **Synthetic first.** Scripts built from the Part A field vocabulary plus the six categories
  above, read by consented non-patient speakers. `DemoPatientProfile`'s personas are the natural
  seed for the clinical content, expanded from term lists into the rambling utterances they would
  actually produce. Writing those expansions is itself the first dataset deliverable and needs no
  recording equipment.
- **Pilot audio only under separate written consent** for recording and model development,
  distinct from the clinical consent at `presentation/consent/` (REQ-TRS-01). Reusing clinical
  consent for model training is both a DPDP problem and a research-ethics problem.
- **Corpus stored outside this repo**, outside the app, referenced by hash in the SBOM and DHF.
- **`drishti_pipeline` still does not exist in this repository.** Re-verified this session. If it
  exists elsewhere, point at it and this section gets rewritten against it.

### 5.4 Metrics: entity-level and negation over WER, reaffirmed with better evidence

WER is the wrong primary metric and there is now Indian-healthcare-specific evidence for why, not
just a general principle. Eka Care's worked drug-name case scores 50% traditional WER, 8.3%
semantic WER, and 33% keyword error rate for a substitution that is clinically critical; their
argument is that conventional WER "treats all words equally, counting substitutions, deletions,
and insertions without considering the semantic or functional importance of different words", and
they propose a keyword-weighted metric focused on "drug names, dosages, vital signs, symptoms"
([Eka Care](https://www.eka.care/services/beyond-traditional-wer-the-critical-need-for-semantic-wer-in-asr-for-indian-healthcare)).
That is independent convergence on the prior memo's D.1 recommendation from a team working the
same market.

Gates, updated from the prior memo's D.4 with the new categories:

| Metric | Gate | Why |
|---|---|---|
| Clinical entity accuracy, narrative slots | >= 95% | Below this the confirmation burden exceeds the typing saved |
| **Negation accuracy** | **100% on the negation subset** | A flipped negation is a wrong record that reads as correct. Zhou 2018 puts substitutions at 18.2% of SR errors; a one-word negation flip is a substitution |
| Numeric-leak rate into narrative slots | **0%** | The A.3 exclusion depends on it; category 4 of 5.2 is the test |
| Irrelevant-content non-routing | 100% under 3.4's architecture (no router exists, so this is a design assertion to re-verify if extraction is ever added) | Category 2 of 5.2 |
| Code-mixed degradation, measured not gated | Recorded per release | Expect 30 to 50% relative WER increase; the number is the trigger for the Indic LoRA path |
| Production edit distance, first 4 weeks | median 0, p90 below 20% of field length | From the `VOICE_FIELD_EDITED` audit payload |
| **Production confirm-without-edit rate plus dwell time** | **new: flag for review if median dwell is under 2 seconds** | This is the rubber-stamp detector that section 1.3's evidence demands. Not in the prior memo. Add it |
| Latency, end of speech to suggestion, mid-range Android | under 1.5 s | Above this the worker types faster than they wait |

---

## RECOMMENDATIONS: updates to the six DECISION GATE items

These update the prior memo's gate. They do not replace it; unchanged items are marked as such.

**1. MVP English model. UNCHANGED, and reinforced.**
Streaming Zipformer transducer on sherpa-onnx, Apache-2.0 lineage. Task 0 adds a new and stronger
argument for it that has nothing to do with accuracy: it is the **only** option where "audio never
leaves the device" is an architectural property rather than a request to a third-party service
that documents its own hint as possibly having "no effect". Parakeet's CC-BY-4.0 remains a live
decision item. Whisper-small remains a recommend-against, and the JMIR hallucination finding
(11.5%) plus its documented tendency to produce fluent text on silence make it worse for a
confirmation-gated field than the prior memo already argued.

**2. The single first voice field. CHANGED, with a new option.**
The prior memo offered `impactOnDailyActivities` (safe) or `AilmentEntry.description` (valuable).
Task 3 adds a third and better framing: **`Consultation.relevantHistory` or
`impactOnDailyActivities` as the first voice field, paired in the same release with converting
`chiefComplaint` from free text to a symptom-vocabulary multi-select.** The pairing is what makes
it coherent: the ramble gets a narrative home a human reads, and the model's input simultaneously
gets *more* structured, not less. Recommendation stands at `impactOnDailyActivities` for the
voice half, on the provably-off-`KernelPayload` reasoning.

**3. Provenance enum values. UNCHANGED.**
`FieldProvenance { TYPED, VOICE_UNCONFIRMED, VOICE_CONFIRMED, VOICE_EDITED }`, one nullable TEXT
column per voice-enabled field, `VOICE_UNCONFIRMED` refused at the repository write boundary.
Section 5.4 adds a reason to keep `VOICE_EDITED`: it is half of the rubber-stamp detector.

**4. Dataset work parallel or after. CHANGED.**
Previously "after PR 3, parallel with PR 4". Revised: **start the written half now, in parallel
with PR 0.** Expanding `DemoPatientProfile`'s personas into realistic rambling utterances across
the six categories of 5.2 needs no audio, no equipment, no consent, and no model, and it is the
artifact that will settle Task 3's open question. Recording starts after PR 3 as before.

**5. Ship PR 0 first. CHANGED: severity upgraded, scope widened.**
Previously "yes, ship PR 0 and PR 0b first". Now: **PR 0 is a privacy-severity item (section 0.4),
and the interim posture should be to disable the voice affordance, not just gate it.** Leaving a
gated but still-possibly-uploading recogniser in place fixes the safety half and leaves the DPDP
half open. Add to PR 0b: register the value-blind PHI guard gap (R-2) as its own hazard row
alongside H-15.

**6. Off-backend recognizer check. ANSWERED from the code; device confirmation still required.**
The code says yes it can go off-device (no `EXTRA_PREFER_OFFLINE`, `INTERNET` granted, default is
"either network or offline"). Treat the device check as confirmatory. The burden has flipped: it
is now on evidence that it does not transmit.

**7. NEW GATE ITEM: does `symptom_string` stay terse?**
The most consequential open question in this memo and it was not in the prior gate. Options:
(a) **Recommended:** `chiefComplaint` becomes a human-tapped multi-select over a fixed symptom
vocabulary; narrative goes to a narrative field and never to the wire. (b) Narrative is sent, and
then the three backend prerequisites in 3.4 are mandatory, including a measured terse-versus-
narrative accuracy comparison against the real kernel first. (c) Status quo: free text, unmeasured.
Note that (c) is what ships today and it is the option nobody chose.
**This decision blocks the design of the first voice slice**, because it decides whether voice
output ever touches a model input at all.

**8. NEW GATE ITEM: who produces the symptom vocabulary?**
Option (a) above needs the list of symptom terms `symptom_model.json` was trained on. That list is
in the SaMDClassifier repo (`symptom_model_meta.json` is already cited in
`TrainedIcdCandidate.kt:5` for the labels list; the *feature* vocabulary is the sibling question).
Someone must fetch it. Without it, the pick-list is a guess and would be worse than free text.
This is a small, concrete, unblocking task and it should be assigned now.

---

## FORMATTING BOUNDARY STATEMENT

For pinning into the codebase now (KDoc on the tidy module and on `TranscriptionService`), and
into `docs/` later under change control.

> **Formatting boundary for voice-derived text.** Automatic processing of an ASR transcript in
> this application is limited to deterministic formatting that changes the form of the text and
> never its content. A transformation is permitted only if it is deterministic, versioned under an
> explicit `tidy_rule_version`, content-preserving, salience-neutral, and negation-inert: it may
> not add, remove, reorder across a clause boundary, or substitute any token carrying clinical
> meaning, and it may not alter, attach, detach, or rescope a negation. Whitespace normalisation,
> leading capitalisation, Unicode normalisation, whole-token removal of fillers from a closed
> list, and standalone spoken-number normalisation are permitted; the last of these may never
> produce a value-and-clinical-unit pair, because a narrative field must never come to contain a
> measurement. Sentence segmentation, removal of speaker attribution, de-duplication of repeated
> phrases, spell correction, case folding, and truncation are **not** permitted as automatic
> transformations, because each can change clinical meaning while remaining fluent. Extraction of
> narrative into structured slots, negation resolution, salience selection, summarisation, and any
> model-authored rewrite are **never** automatic: they are interpretation, not formatting, and
> auto-applying them would remove the human from the step at which a model's reading becomes the
> record, which is the premise the application's software-safety-class argument rests on. If
> extraction is ever offered, it may exist only as a constrained-decoding suggestion that emits
> either a member of a fixed enumeration or a contiguous span of the verbatim transcript, that
> lands in `VOICE_UNCONFIRMED`, that a human accepts one slot at a time against the retained
> verbatim transcript displayed alongside it, and that emits a provenance-only audit breadcrumb on
> every suggest, accept, edit, and reject. The operational test is this: **if a suggested value
> contains a token that does not appear in the verbatim transcript, and the target slot is not an
> enumeration, the system is authoring rather than parsing, and the design is wrong.** The verbatim
> transcript is retained as encrypted clinical data and is the source of truth; the audio is not
> retained; the audit log carries provenance only and never content.

---

## Sources

Clinical ASR and scribe evidence:
- [Zhou L et al., Analysis of Errors in Dictated Clinical Documents Assisted by Speech Recognition Software and Professional Transcriptionists, JAMA Network Open 2018;1(3):e180530](https://pmc.ncbi.nlm.nih.gov/articles/PMC6203313/)
- [Goss FR, Zhou L, Weiner SG, Incidence of speech recognition errors in the emergency department, Int J Med Inform 2016](https://pubmed.ncbi.nlm.nih.gov/27435949/)
- [Quality of Clinical Notes Created by Ambient Listening Generative AI: Pragmatic Prospective Pilot Study, JMIR Medical Informatics 2026;1:e86474](https://medinform.jmir.org/2026/1/e86474)
- [The Joint Commission, Quick Safety 12: Speech recognition technology translates to patient risk](https://www.jointcommission.org/resources/news-and-multimedia/newsletters/newsletters/quick-safety/quick-safety--issue-12-transcription-translates-to-patient-risk/transcription-translates-to-patient-risk/)
- [Beyond human ears: navigating the uncharted risks of AI scribes in clinical practice, npj Digital Medicine](https://www.nature.com/articles/s41746-025-01895-6) (indexed; full text not retrievable, paywall redirect)
- [Evaluating the Quality and Safety of Ambient Digital Scribe Platforms Using Simulated Ambulatory Encounters](https://www.sciencedirect.com/science/article/pii/S2949761225000999) (indexed; HTTP 403, not retrievable)

Automation bias:
- [Automation bias in electronic prescribing, PMC5356416](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5356416/)
- [Impact of AI recommendation correctness on diagnostic accuracy in clinical decision-making](https://www.sciencedirect.com/science/article/pii/S138650562500440X)
- [Bias recognition and mitigation strategies in artificial intelligence healthcare applications, npj Digital Medicine](https://www.nature.com/articles/s41746-025-01503-7)

Ambient scribe products, retention and medico-legal:
- [All About AI Scribes: FAQs for Health Systems and Providers, McDermott](https://www.mcdermottlaw.com/insights/all-about-ai-scribes-faqs-for-health-systems-and-providers/)
- [Ambient AI Scribes: Efficiency Gains vs Emerging Privacy and Cybersecurity Risks, ABA Health Law](https://www.americanbar.org/groups/health_law/news/2026/ambient-ai-scribes-privacy-cybersecurity/)
- [The risks of using virtual scribes and ambient listening for documentation, McAfee & Taft](https://www.mcafeetaft.com/healthcarelinc-qa-the-risks-of-using-virtual-scribes-and-ambient-listening-for-documentation/)
- [Augnito founding story, Analytics India Magazine](https://analyticsindiamag.com/ai-startups/indias-first-speech-recognition-system-for-healthcare-industry-the-startup-story-of-augnito/)

Indian-language and Indian-accent ASR:
- [Svarah: Evaluating English ASR Systems on Indian Accents](https://www.academia.edu/111680794/Svarah_Evaluating_English_ASR_Systems_on_Indian_Accents)
- [LAHAJA: A Robust Multi-accent Benchmark for Evaluating Hindi ASR Systems, arXiv 2408.11440](https://arxiv.org/pdf/2408.11440)
- [Code Switched and Code Mixed Speech Recognition for Indic languages, arXiv 2203.16578](https://arxiv.org/pdf/2203.16578)
- [Beyond Traditional WER: Semantic WER for ASR in Indian Healthcare, Eka Care](https://www.eka.care/services/beyond-traditional-wer-the-critical-need-for-semantic-wer-in-asr-for-indian-healthcare)
- [Voice Recognition Accuracy Across Indian Languages in Healthcare Chatbots, IJRSML](https://ijrsml.org/voice-recognition-accuracy-across-indian-languages-in-healthcare-chatbots/)

Frontline health worker context:
- [Design Challenges in the ASHA Diary, Springer](https://link.springer.com/chapter/10.1007/978-981-96-5487-1_11)
- [Exploring the Digital Challenges Faced by Community Health Workers in a District of Telangana, India](https://www.researchgate.net/publication/396020108_Exploring_the_Digital_Challenges_Faced_by_Community_Health_Workers_in_a_District_of_Telangana_India_A_Qualitative_Study)
- [India's Digital Health Push Is Overworking Its Front-Line Women, New Lines Magazine](https://newlinesmag.com/reportage/indias-digital-health-push-is-overworking-its-front-line-women/)
- [From Care Labor to Data Labor: India's Door-to-Door Health Activists, Data & Society](https://datasociety.net/points/from-care-labor-to-data-labor-indias-door-to-door-health-activists/)

Android platform:
- [RecognizerIntent.EXTRA_PREFER_OFFLINE (added API 23)](https://learn.microsoft.com/en-us/dotnet/api/android.speech.recognizerintent.extrapreferoffline?view=net-android-34.0)
- [SpeechRecognizer.CreateOnDeviceSpeechRecognizer (added API 31)](https://learn.microsoft.com/en-us/dotnet/api/android.speech.speechrecognizer.createondevicespeechrecognizer?view=net-android-34.0)

Regulatory context:
- [FDA Clinical Decision Support Software guidance](https://www.fda.gov/regulatory-information/search-fda-guidance-documents/clinical-decision-support-software)
- [FDA CDS Software FAQs](https://www.fda.gov/medical-devices/software-medical-device-samd/clinical-decision-support-software-frequently-asked-questions-faqs)
