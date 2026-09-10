# Classifier Wire-Format Investigation (STEP 1, read-only on code, model run read-only)

Run date: 2026-08-31. SaMD-App branch `docs/asr-vocab-and-h16`, HEAD `a1f9176`.

This memo settles PR 7a DECISION GATE items 2 and 7 from
`scratchpad/pr7a-structured-capture-design-memo.md` against the real classifier and its real
training data, both of which are on this machine and neither of which the earlier memos could read.

**The headline, stated first because it changes the build.** The design direction (a human-tapped
pick-list instead of free text) is measurably correct: resolving to the classifier's own trained
symptom tokens beats today's free text by **+5.5 points top-1 accuracy** (94.1% vs 88.6%, n=1500).
But the **~41-item vocabulary as drafted is a 30-point accuracy regression** (58.0% vs 88.6%),
and fixing the string form does not recover it. The problem is not the wire format. It is that a
41-item vocabulary collapses the classifier's 118 discriminative symptom terms into about 22
buckets, and the discriminative signal lives almost entirely in the qualifiers that the collapse
destroys. 12 of 13 generic-versus-qualified term pairs tested **flip the top-1 diagnosis**.

---

## 0. Terrain, and the read-only guarantee

| Thing | Absolute path |
|---|---|
| SaMD-App (this repo) | `/media/sandesh/extra-ssd/AndroidWork/SaMDApp` |
| SaMDClassifier | `/media/sandesh/extra-ssd/AndroidWork/SaMDClassifier` |
| Dataset-make / Synthea | `/media/sandesh/extra-ssd/dataset/dataset-make` |
| Synthea proper | `/media/sandesh/extra-ssd/dataset/dataset-make/synthea` and `.../synthea-international` |
| Canonical training dataset | `/media/sandesh/extra-ssd/AndroidWork/SaMDClassifier/dataset/canonical_dataset.csv` (8.3 MB) |
| Trained artifacts | `/media/sandesh/extra-ssd/AndroidWork/SaMDClassifier/models/` |

Both external projects are git repos and both were read, not written.

- **SaMDClassifier**: branch `main`, HEAD `f1e4b27`. `git status --short` at the START of this
  session showed three pre-existing modified files, all RAG vector-store binaries:
  `src/rag/vector_store/56d6712d-.../length.bin`, `src/rag/vector_store/9c3b2e5e-.../length.bin`,
  `src/rag/vector_store/chroma.sqlite3`. `git status --short` at the END of this session showed
  **the identical three files and nothing else**. Nothing was added, nothing was staged, nothing
  was committed. The dirty state is not mine and was not touched.
- **dataset-make**: branch `main`, HEAD `d32a197`. Read only, never written. It also carries a
  pre-existing dirty tree (modified files under `drishti_pipeline/scratch/` and `output_india/csv/`,
  plus untracked run output). Not mine: `stat` puts their mtimes at 2026-08-19, twelve days before
  this session, and the only commands run against that tree were `ls` and `git rev-parse`.

Independently verifiable: `models/symptom_model.json` and `dataset/canonical_dataset.csv` both
still carry their 2026-07-23 mtimes, and `SaMDClassifier/src/rag/vector_store/chroma.sqlite3`
carries 2026-08-12. Nothing this session touched was written to.

What was actually run: `python -c` and heredoc scripts through
`/media/sandesh/extra-ssd/AndroidWork/SaMDClassifier/.venv/bin/python` (sklearn 1.5.0,
xgboost 2.0.3), importing `src/refine_diagnosis.py`. That module loads the two saved XGBoost models,
the two saved vectorizers and the label encoder, and reads `canonical_dataset.csv` to build a tier
profile. It writes nothing. `scripts/train_symptom_classifier.py` and `scripts/train_model.py` were
**read but never executed**, so no model or dataset file was regenerated or overwritten. The FastAPI
service in `src/app.py` was **not** started (it pulls in `pipeline_glue`, hence
sentence-transformers and torch, and it was not needed: `refine_diagnosis` is the module that owns
the symptom path). No secret was read, loaded, printed, or needed. No `.env`, `local.properties` or
`BuildConfig` in any project was opened.

No temporary file was written anywhere. Every script ran from stdin as a heredoc. The only file
this session created is this memo.

### Incidental correction to an earlier memo

`scratchpad/asr-field-audit-memo.md` C-4 states that `drishti_pipeline` "does not exist in this
repository". That is true of SaMD-App and remains true. But it **does** exist on this machine, at
`/media/sandesh/extra-ssd/dataset/dataset-make/drishti_pipeline/` (`run_pipeline.py`,
`step1_generate.py`, `config.py`, `preflight_check.py`, `README.md`). C-4's follow-up sentence, "if
it exists elsewhere and has a synthetic-data discipline worth inheriting, point this memo at it",
is now answerable: it is there. Out of scope for this investigation, recorded so it is not
re-derived a third time.

---

## 1. What `symptom_string` actually is in training (settles gate item 2)

### 1.1 The pipeline, read from the training script

`SaMDClassifier/scripts/train_symptom_classifier.py`:

- `MODEL_VERSION = "symptom-clf-v0.1-tfidf-xgboost"` (line 17); the shipped
  `models/symptom_model_meta.json` records `"symptom-clf-v0.2-enriched-symptom-pool"`, so the
  checked-in script is one version behind the checked-in model. Recorded, not resolved.
- Input column is `symptom_string`, label is `icd_candidate` (lines 44 to 49). Rows with a null
  `icd_candidate` are dropped, which is 32.2% of the dataset by design (line 30, and the
  `row_filter` note in the meta file).
- **Two** vectorizers, horizontally stacked (lines 41 to 46):

      word_vectorizer = TfidfVectorizer(ngram_range=(1, 2))
      char_vectorizer = TfidfVectorizer(analyzer="char_wb", ngram_range=(3, 5))
      X = hstack([X_word, X_char]).tocsr()

- Classifier is `xgb.XGBClassifier(objective="multi:softprob")` over 18 ICD classes.

Verified live against the saved artifacts, not inferred from the script:

    WORD vectorizer:  lowercase=True  token_pattern='(?u)\b\w\w+\b'  ngram_range=(1,2)
                      strip_accents=None  stop_words=None  min_df=1  vocab size 2374
    CHAR vectorizer:  lowercase=True  analyzer='char_wb'  ngram_range=(3,5)  vocab size 2166
    combined feature dim 4540   (matches symptom_model_meta.json)

### 1.2 Inference-time preprocessing: there is none

`SaMDClassifier/src/refine_diagnosis.py:76-82`:

    def get_symptom_ranked(symptom_string):
        xw = word_vectorizer.transform([symptom_string])
        xc = char_vectorizer.transform([symptom_string])
        xin = hstack([xw, xc]).tocsr()
        probs = symptom_model.predict_proba(xin)[0]
        return sorted(zip(icd_labels, probs), key=lambda p: p[1], reverse=True)

The raw string goes straight into `transform`. No lowering, no stripping, no splitting, no
normalisation of any kind beyond what the two vectorizers do internally. `src/app.py:194` calls
`refine_diagnosis.refine(payload.symptom_string, refine_vitals)` with the request field
untouched. So the vectorizers' own settings are the entire contract.

### 1.3 The real values in the training data

Whole-file scan of `dataset/canonical_dataset.csv`:

    rows with a non-empty symptom_string: 22215     of which labelled: 15105
    rows containing '|' : 22215  (100.0%)
    rows containing ',' :     0  (0.0%)
    distinct symptom terms: 118
    terms containing any uppercase character: 0
    mean terms per encounter: 3.10

Verbatim sample rows (`symptom_string` -> `icd_candidate`):

    'general weakness | poor sleep'                                -> ''
    'sweating | body ache | sense of dread'                        -> 'F41.0'
    'weight loss | general weakness | hemoptysis | loss of appetite'-> 'A15'
    'poor sleep | no specific symptom | mild discomfort'           -> ''
    'sweating | restlessness'                                      -> 'F41.0'

Most frequent terms, with counts:

    9691 general weakness   9069 poor sleep      8068 mild discomfort   3863 no symptoms
    3852 joint pain         3771 fatigue         3139 exertional dyspnoea
    2787 no specific symptom 1442 headache       1378 knee pain on weight-bearing
    1350 reduced mobility   1342 morning stiffness under 30 minutes    1318 joint swelling
    1232 nausea             1054 dizziness        832 abdominal pain     653 loss of appetite
     521 blurred vision      472 epistaxis        424 fever              413 high fever
     411 body ache           382 vomiting         338 confusion          337 arthralgia

### 1.4 Measured sensitivity: what actually matters, and what does not

Rather than reason about the vectorizers, the same symptom content was pushed through the live
model in five renderings. `L1` is the summed absolute difference across all 18 class
probabilities against the training form.

    input: general weakness / poor sleep / joint pain
    pipe-spaced 'a | b | c'  (training form)  top3 E66:0.734 M17:0.256 I10:0.003   L1 = 0.000000
    comma-space 'a, b, c'                     top3 E66:0.734 M17:0.256 I10:0.003   L1 = 0.001274
    period-join 'a. b. c'                     top3 E66:0.734 M17:0.256 I10:0.003   L1 = 0.001274
    TitleCase   'A | B | C'                   top3 E66:0.734 M17:0.256 I10:0.003   L1 = 0.000000
    pipe-NOSPACE 'a|b|c'                      top3 M17:0.639 E66:0.321 I10:0.022   L1 = 0.826594

Three conclusions, each measured rather than argued:

1. **Casing is completely irrelevant.** `L1 = 0.000000` exactly. Both vectorizers have
   `lowercase=True`, so the model cannot see case at all.
2. **The delimiter character barely matters, provided it is surrounded by whitespace.** Pipe,
   comma and period all land within 0.0013 of each other, identical to three decimal places on
   every top-3 probability. The word vectorizer's `token_pattern` treats every non-word character
   as a separator, so `|` and `,` tokenize identically:

       analyzer("fever | body ache | dry cough") == analyzer("fever, body ache, dry cough")
         == ['fever','body','ache','dry','cough','fever body','body ache','ache dry','dry cough']

3. **Whitespace around the delimiter matters a great deal.** Removing the spaces flips the top-1
   class and moves 0.83 of total probability mass. The cause is `analyzer="char_wb"`, which splits
   on whitespace first and then builds character n-grams inside each resulting token: with no
   spaces, `weakness|poor` becomes one pseudo-word and generates character n-grams that appear
   nowhere in training.

This is the risk the design memo did not identify. Its gate item 2 debated pipe versus comma, which
is nearly a non-question; the real hazard is a delimiter with no surrounding spaces.

### 1.5 CONCLUSION for gate item 2

Measured on 1500 labelled rows, same content, five renderings:

    rendering                              top1     top3
    A pool terms ' | ' (training form)    0.941    0.995
    B pool terms ', '                     0.924    0.995
    E pool terms '. '                     0.927    0.995
    D pool terms '|' (no spaces)          0.907    0.985
    C prose free text (today's shape)     0.886    0.985

**Corrected answer, which changes the design memo's recommendation on one of three parts:**

| Part | Memo recommended | Measured verdict |
|---|---|---|
| (a) term form | English clinical term | **Confirmed, with a large caveat.** It must be the classifier's *own* trained term, not a generic English label. Section 2 |
| (b) delimiter | `", "` comma-space, citing `api-contract.md:902` | **CORRECTED to `" | "` pipe-with-spaces.** The training data is 100% pipe-spaced and 0% comma. Pipe scores 94.1% vs comma's 92.4% top-1, a real if modest 1.7-point gap. `api-contract.md:902`'s `"fever, body ache, dry cough"` is a documentation example that does not match the training distribution; `DemoPatientProfile`'s pipe format was the one that did. The memo picked the wrong artifact as authoritative |
| (c) casing | lowercase | **Confirmed but irrelevant.** `L1 = 0.000000` between cased and lowercased input. Harmless to keep for tidiness; it buys nothing and should not be described as a safety property |

The load-bearing rule the memo missed, and the one that must be a test:
**terms must be whitespace-separated.** A join that produces `a|b|c` costs 3.4 points and can flip
top-1. Recommend the exact separator string `" | "` and an assertion that the emitted value never
contains a delimiter without surrounding spaces.

---

## 2. The OOV check: the vocabulary against the classifier's real vocabulary

This is the check no earlier memo could run, and it is the finding that changes the build.

### 2.1 What the classifier actually knows

18 ICD classes (`models/symptom_model_meta.json` `labels`), identical to
`SaMDApp/app/src/main/java/com/example/samdapp/domain/model/TrainedIcdCandidate.kt`'s
`TRAINED_ICD_CANDIDATES`. That file's KDoc is accurate and confirmed.

118 distinct symptom terms in the training pool. That is the authoritative input vocabulary. It is
not a general primary-care presenting-complaint vocabulary; it is a synthetic phrase pool built to
discriminate those 18 conditions.

### 2.2 Status of each proposed clinicalTerm

Exact-match test against the 118-term pool, plus a check on which content words are absent from the
word vectorizer's 2374-entry vocabulary. Only **6 of 41** proposed terms are exact matches.

| # | Proposed clinicalTerm | Status | Exact trained token(s) to emit instead |
|---|---|---|---|
| 1 | Fever | **in-vocab** | `fever` (but see 2.4: needs qualifier expansion) |
| 2 | Cough / cold | variant | `productive cough`, `chronic cough over 2 weeks`. No bare `cough` exists |
| 3 | Body ache / generalized pain | variant (`generalized` unseen) | `body ache`, `severe body ache`, `muscle ache` |
| 4 | Headache | **in-vocab** | `headache` (needs qualifier expansion) |
| 5 | Weakness / tiredness | variant | `general weakness`, `weakness`, `fatigue`, `tiredness`, `lethargy`, `exhaustion` |
| 6 | Abdominal pain | **in-vocab** | `abdominal pain`, `lower abdominal pain`, `abdominal cramps` |
| 7 | Acidity / gas / heartburn | **OOV, no overlap** | none. Model has never seen it |
| 8 | Diarrhoea / loose motions | variant (`motions` unseen) | `loose stools`, `constipation or diarrhoea` |
| 9 | Vomiting / nausea | variant | `vomiting`, `nausea` (two separate pool terms) |
| 10 | Dizziness / giddiness | variant (`giddiness` unseen) | `dizziness`, `dizziness on standing` |
| 11 | Joint pain / back / neck | variant (`back` unseen) | `joint pain`, `knee pain`, `neck stiffness`. No back-pain term exists |
| 12 | Breathlessness | **in-vocab** | `breathlessness`, `exertional dyspnoea`, `breathlessness on exertion` |
| 13 | Burning urination | variant | `burning micturition`, `increased urinary frequency` |
| 14 | Skin complaint / itching | variant (`complaint`, `itching` unseen) | `skin rash`, `dry skin` |
| 15 | Palpitations / panic | variant (`panic` unseen) | `palpitations`, `heart pounding`, `racing heart` |
| 16 | Sore throat | **OOV, no overlap** | none. Measured inert, see 2.3 |
| 17 | Nasal congestion / sneezing | **OOV, no overlap** | none |
| 18 | Chest pain | variant | `chest discomfort`, `chest tightness`, `chest pain on breathing`. No bare `chest pain` exists |
| 19 | Loss of appetite | **in-vocab** | `loss of appetite`, `poor appetite` |
| 20 | Weight loss | **in-vocab** | `weight loss`, `unexplained weight loss` |
| 21 | Swelling / oedema | variant (`oedema` unseen) | `joint swelling` only. No general oedema term |
| 22 | Tension / worry / unwell | **OOV, no overlap** | `mild anxiety`, `restlessness`, `sense of dread` are the nearest trained terms |
| 23 | Sleep problem | variant (`problem` unseen) | `poor sleep` |
| 24 | Known high BP / on BP meds | **OOV** | none, and correctly so: this is a vitals input, not a symptom |
| 25 | Known diabetes / sugar | **OOV, no overlap** | none, same reason |
| 26 | Ear pain / discharge | **OOV, no overlap** | none |
| 27 | Eye complaint / vision | variant | `blurred vision`, `visual aura`, `photophobia` |
| 28 | Injury / trauma | **OOV, no overlap** | none. Measured inert |
| 29 | Toothache | **OOV, no overlap** | none |
| 30 | Tingling / numbness | **OOV, no overlap** | none |
| 31 | White vaginal discharge / leucorrhoea | **OOV, no overlap** | none |
| 32 | Menstrual problem | **OOV, no overlap** | none. Measured inert |
| 33 | Antenatal check / pregnancy | **OOV, no overlap** | none |
| 34 | Pregnancy danger signs | **OOV** | none |
| 35 | Child fever | variant (`child` unseen) | `fever`, `high fever` |
| 36 | Cough / difficult breathing | variant (`difficult` unseen) | `productive cough`, `trouble breathing` |
| 37 | Diarrhoea (child) | variant (`child` unseen) | `loose stools` |
| 38 | Ear problem | **OOV, no overlap** | none. Measured inert |
| 39 | Malnutrition / not growing | **OOV, no overlap** | `not hungry` is nearest, and is a poor fit |
| 40 | Pallor / anaemia | variant (`anaemia` unseen) | `pallor` |
| 41 | Other, described by worker | **OOV by design** | must emit **nothing** |

Roughly 17 of the 41 items have no usable trained equivalent at all. These are precisely the
primary-care presentations a real PHC sees most and this 18-condition synthetic classifier was never
built for: acidity, sore throat, nasal congestion, ear problems, injury, toothache, tingling,
leucorrhoea, menstrual problems, antenatal care, malnutrition.

### 2.3 An OOV term is not merely useless. Some are confidently wrong.

Each proposed term was submitted alone and compared against the empty-string baseline, which is
what the model returns with zero usable signal.

    EMPTY-STRING BASELINE ("no signal"):  E66 0.7495, I10 0.0709, M17 0.0397, A90 0.0337, B54 0.0308

Two distinct failure modes appear, and the second is worse:

**Inert** (the model returns the prior; the selection is invisible to it):

    'Sore throat'                L1 vs prior 0.076   top1 E66 0.788
    'Injury / trauma'            L1 vs prior 0.039   top1 E66 0.735
    'Menstrual problem'          L1 vs prior 0.004   top1 E66 0.749
    'Ear problem'                L1 vs prior 0.003   top1 E66 0.748

**Spurious** (OOV, yet the character n-grams manufacture a confident and wrong answer):

    'Diarrhoea / loose motions'  ->  M17 (knee osteoarthritis)   0.644
    'Swelling / oedema'          ->  M17                          0.712
    'Skin complaint / itching'   ->  M17                          0.598
    'Nasal congestion / sneezing'->  M17                          0.461
    'Toothache'                  ->  B54 (malaria)                0.437
    'Child fever'                ->  N39.0 (UTI)                  0.416

The spurious class is the dangerous one. It is the app's existing worst failure shape: a wrong
answer with a plausible confidence attached, stamped `REAL_INFERENCE`, with nothing in any layer
that logs or warns. It is the same shape the empty-differential fabrication fix and H-09 exist to
prevent.

**Note also that the empty-string prior is E66 (Obesity) at 74.95%.** Sending an empty
`symptom_string` does not produce an empty or low-confidence result; it produces a confident-looking
Obesity differential. This is direct measured support for the design memo's gate item 6
recommendation (do not call evaluate when the resolved term list is empty; stamp
`InferenceSource.UNAVAILABLE` instead). That recommendation was made on principle; it is now
evidence-backed.

### 2.4 The real problem is granularity, not spelling. Qualifiers flip the diagnosis.

Fixing every string in the table above does **not** fix the vocabulary. Measured over the labelled
dataset, for each pool term, the label it most often co-occurs with:

    generic term      -> top label     qualified term                       -> top label    FLIP?
    fever              A09   48%       stepladder fever                      A01.0   88%    YES
    fever              A09   48%       cyclical high fever with chills         B54   88%    YES
    fever              A09   48%       evening fever                           A15   77%    YES
    fever              A09   48%       high fever                              A90   54%    YES
    body ache          B54   94%       severe body ache                        A90   87%    YES
    headache           I10   49%       retro-orbital pain                      A90   90%    YES
    headache           I10   49%       unilateral throbbing headache         G43.9   83%    YES
    headache           I10   49%       severe headache                         I10   89%    no
    joint pain         E66   67%       knee pain on weight-bearing             M17   99%    YES
    joint pain         E66   67%       severe joint pain                     A92.0   89%    YES
    breathlessness     A91   40%       exertional dyspnoea                     E66  100%    YES
    breathlessness     A91   40%       breathlessness on exertion              D50   84%    YES
    abdominal pain     A09   28%       lower abdominal pain                  N39.0   93%    YES

**12 of 13 pairs flip the top-1 diagnosis.** The single word "severe" in front of "body ache" moves
the answer from malaria to dengue. "Exertional" in front of "dyspnoea" moves it from dengue
haemorrhagic fever to obesity with 100% purity. A 41-item vocabulary that offers one "Fever" chip
and one "Joint pain" chip throws all of this away at the point of capture.

Most label-specific terms in the pool, all of which a 41-item vocabulary collapses:

    99.6% of 2689 rows -> E66    'exertional dyspnoea'
    99.1% of 1372 rows -> M17    'knee pain on weight-bearing'
    98.9% of 1315 rows -> M17    'joint swelling'
    98.9% of 1333 rows -> M17    'morning stiffness under 30 minutes'
    98.5% of 1344 rows -> M17    'reduced mobility'
    97.8% of  465 rows -> I10    'epistaxis'
    92.0% of  188 rows -> A01.0  'rose spots'
    90.2% of  183 rows -> A90    'retro-orbital pain'

---

## 3. The accuracy pass (settles gate item 7)

The classifier ran locally, read-only, via `refine_diagnosis.get_symptom_ranked`. 1500 labelled rows
sampled with `random.seed(7)` from the 15105 labelled rows in `canonical_dataset.csv`. For each row
the same underlying symptom content was rendered five ways and scored against the row's true
`icd_candidate`.

    n = 1500                                          top1     top3
    A  full pool terms, ' | '     (ceiling)          0.941    0.995
    C  prose free text            (TODAY'S SHAPE)    0.886    0.985
    F  41-item picks -> exact pool tokens            0.580    0.888
    G  hybrid: picks + free-text narrative           0.880    0.979
    H  118-item pick-list (the pool itself)          0.941    0.995

    top1 delta versus today's free text:
       A  +0.055
       H  +0.055
       F  -0.306
       G  -0.006

Row C's prose rendering was generated as
`"Patient complains of {a}, {b} and {c} since a few days. No other issues reported."`, a
deliberately realistic stand-in for the sentence a worker types into the "Main concern" box today.

An earlier run also measured the vocabulary's clinicalTerm **labels** sent verbatim (no resolution
to trained tokens) at **0.583 top-1**. Resolving those labels to the exact trained tokens moved it
to 0.580. The difference is noise. **Fixing the string form recovers nothing.**

### 3.1 What this says, plainly

- **The pick-list direction is right.** A pick-list over the classifier's own term set is +5.5
  points on top-1 versus free text, and it eliminates the H-16 value-level PHI path. Both halves of
  the design memo's argument survive.
- **The 41-item vocabulary as drafted is a STOP.** At -30.6 points it is not a marginal call. It
  would ship a measurable, large accuracy regression on the only model input the app controls, in
  exchange for a data-quality and PHI improvement. That trade is not acceptable and the design memo
  claimed the opposite ("makes the model input more in-distribution, not less"). That claim is
  **contradicted by measurement** and must be withdrawn as written.
- **The hybrid is not a rescue.** Appending the narrative to the picks lands at 0.880, statistically
  indistinguishable from free text, and it puts patient narrative back on `symptom_string`, which
  reopens H-16 at full severity. It buys nothing and costs the hazard closure.
- **The fix is granularity.** Row H is the same 94.1% as row A: a pick-list whose items *are* the
  classifier's 118 trained terms performs at the ceiling. The gap between 41 items and 118 items is
  the entire regression.

### 3.2 The honest limit of these numbers

`SaMDClassifier/readme.md:103-109` states the limitation in the project's own words:

> **Known limitation, training data has no spelling/phrasing variance.** `symptom_string` is
> sampled from a small fixed per-condition phrase pool (dataset generation, not real patient
> entry). Verified directly: individual symptom phrases *are* shared across multiple conditions
> (e.g. "fatigue" appears under `E66`, `E11`, and `D50`), but each condition's *3-phrase
> combination* pool is disjoint from every other condition's, so the training/test data is
> perfectly linearly separable by TF-IDF.

So every absolute number above measures how well a rendering preserves a synthetic dataset's
built-in separability, not real-world clinical accuracy. None of these figures is a clinical
performance claim and none should be quoted as one.

The **relative** comparison is what the gate needs, and it is robust to that caveat: collapsing 118
discriminative terms into 22 buckets destroys the separability the model entirely depends on, and no
change of delimiter, casing or term spelling recovers it. That conclusion does not depend on the
dataset being realistic. It would hold, and probably hold harder, on a noisier one.

A second limit worth stating: the classifier is a synthetic 18-condition model. A real PHC sees
acidity, sore throat, ear infection, injury, dental pain and leucorrhoea constantly, and this model
has no class for any of them. That is a fact about the model's scope, not about the vocabulary, and
it is the strongest argument that the vocabulary research and the classifier are answering two
different questions.

---

## 4. CONCLUSIONS: updates to the PR 7a decision gate

These update `scratchpad/pr7a-structured-capture-design-memo.md`'s DECISION GATE. They do not
replace it. Unchanged items are marked so.

### Gate 2 (wire format): SETTLED, one part corrected

Emit the classifier's own trained tokens, joined with `" | "` (pipe with a space either side).
Casing is free.

    symptom_string = trained tokens for the selected items, joined by " | "
    example: "stepladder fever | rose spots | constipation or diarrhoea"

- (a) term form: **confirmed** as the English clinical term, with the correction that it must be the
  classifier's exact trained token, not a generic label. The mapping table in 2.2 is the source.
- (b) delimiter: **CORRECTED from `", "` to `" | "`.** Training data is 100% pipe-spaced, 0% comma.
  Measured 94.1% vs 92.4% top-1. The memo cited `api-contract.md:902` as authoritative; the training
  data overrules it, and `DemoPatientProfile`'s pipe format was right all along.
- (c) casing: **confirmed but demoted.** Measured effect exactly zero. Keep lowercase for tidiness,
  do not describe it as a control.
- **NEW hard rule the memo missed:** terms must be whitespace-separated. `a|b|c` with no spaces
  costs 3.4 points and can flip top-1, because `char_wb` splits on whitespace first. This belongs in
  a test, not a comment.
- **Unchanged and reconfirmed:** dropping `payload.transcription` from the `symptomString` join.

### Gate 7 (accuracy): MEASURED, and it does not say what the memo assumed

Run on the real classifier against 1500 labelled rows. The pick-list direction gains +5.5 points
when it emits trained tokens at full granularity, and loses 30.6 points at the drafted 41-item
granularity. The memo's assertion that 7a "makes the model input more in-distribution, not less"
is true per token and false per encounter, and per encounter is what the model scores on. Withdraw
the claim as worded and replace it with the measured result.

### Vocabulary corrections required

1. **The data class needs a fourth string, and this is the structural fix.** The design memo's
   `PresentingComplaint` conflates two different jobs in `clinicalTerm`: the doctor-facing and
   dataset-facing English term (the research's value, correct as it stands) and the token the model
   must receive. They are not the same string, and conflating them is what produces the 30-point
   drop. Add a separate field, for example `wireTokens: List<String>`, holding the exact trained
   tokens, with an empty list meaning "emit nothing for this item" (which is right for all 17 OOV
   items and for `other_describe`).
2. **The 41-item flat list must become two-level for the collapsed items.** Selecting "Fever" must
   lead to a qualifier choice (`stepladder fever` / `evening fever` / `cyclical high fever with
   chills` / `high fever` / `mild fever` / plain `fever`), and the same for Joint pain, Headache,
   Breathlessness, Abdominal pain and Body ache. This is not a new invention: it is exactly
   REQ-TRS-04's still-PLANNED half, "dynamically expanding the field set based on the selected
   ailment type" (`docs/requirements/software-requirements.md:223-226`). The measurement turns that
   from a nice-to-have into a prerequisite for the model input to survive the change.
3. **17 items must ship with empty `wireTokens`.** Acidity/gas, sore throat, nasal congestion, ear
   pain, ear problem, injury, toothache, tingling, leucorrhoea, menstrual problem, antenatal check,
   pregnancy danger signs, malnutrition, known BP, known diabetes, swelling as general oedema, and
   `other_describe`. They remain fully valid as clinical record, as report content and as dataset
   rows. They simply must not be sent to a model that has never seen them, because an OOV term is
   not inert: several produce confident wrong answers (2.3).
4. **Per-item exact tokens** are in the table at 2.2. That table is the build input.

### Contradictions found, reported not designed around

- **X-1 (blocking, changes the build).** The design memo's central accuracy claim is contradicted by
  measurement. Shipping the 41-item vocabulary as drafted regresses top-1 by 30.6 points. Section 3.
- **X-2 (corrects gate 2(b)).** The training delimiter is `" | "`, not `", "`.
  `docs/backend/api-contract.md:902` does not reflect the training distribution and should not be
  cited as authoritative for this field again. Section 1.5.
- **X-3 (new hazard, not in any memo or the risk file).** An empty or fully-OOV `symptom_string`
  returns a confident E66 (Obesity) at 74.95%, not a low-confidence or empty result. Any code path
  that can produce an empty resolved term list therefore produces a confident wrong differential.
  This is measured, it is the H-09 failure shape, and it is not currently registered anywhere.
- **X-4 (scope mismatch, and it is the deeper finding).** The classifier is a synthetic
  18-condition model whose 118-term vocabulary was generated to separate those 18 conditions. The
  presenting-complaint research is an all-India primary-care vocabulary. Roughly 17 of 41 items have
  no representation in the model at all. The two artifacts are answering different questions and no
  string mapping reconciles them. Any expectation that the pick-list will improve the model on real
  PHC presentations is unfounded until the model itself is retrained on a presenting-complaint
  distribution.
- **X-5 (minor, recorded).** `scripts/train_symptom_classifier.py:17` declares
  `MODEL_VERSION = "symptom-clf-v0.1-tfidf-xgboost"` while the shipped
  `models/symptom_model_meta.json` records `"symptom-clf-v0.2-enriched-symptom-pool"`. The
  checked-in training script is not the one that produced the checked-in model.
- **X-6 (corrects the field-audit memo's C-4).** `drishti_pipeline` exists at
  `/media/sandesh/extra-ssd/dataset/dataset-make/drishti_pipeline/`. C-4 was right about SaMD-App
  and wrong about the machine.

### What the 7a-i build now depends on

The build cannot proceed on the design memo alone. Before 7a-i:

1. Operator ruling on the two-level qualifier expansion (conclusion 2), because it changes the UI
   design, the data class, and the sub-PR scope.
2. Operator ruling on `wireTokens` as a separate field from `clinicalTerm` (conclusion 1).
3. Operator ruling on X-3, whether the empty and OOV `symptom_string` cases get the
   `InferenceSource.UNAVAILABLE` treatment (the design memo's gate item 6, now evidence-backed) and
   whether X-3 is registered as a hazard row.
4. A decision on X-4: whether PR 7a proceeds knowing the vocabulary and the model are scoped to
   different problems, or whether the classifier's retraining is pulled forward.

`PROGRESS.md` was deliberately not updated: this is pure investigation and nothing was built. The
next step, the 7a-i build, depends on the four rulings above.
