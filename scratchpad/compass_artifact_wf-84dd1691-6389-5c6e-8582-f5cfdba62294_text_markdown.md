# All-India Presenting-Complaint Vocabulary for a Frontline Clinical Intake App
### An evidence-grounded, frequency-ranked seed dataset mapping vernacular idiom → English clinical term → ICPC-2, with India-programme (CBAC / IMNCI / ANC) alignment

## TL;DR
- Across Indian primary-care and OPD studies, the most common presenting complaints/reasons-for-encounter cluster tightly around **fever, cough/cold, body aches and pains, headache, abdominal pain, weakness/tiredness, diarrhoea/vomiting, dizziness, and vernacular somatic idioms (kamzori, gas/acidity, ghabrahat, safed pani, "tension")** — a set of ~35–40 items that recur regardless of region.
- **ICPC-2 (Reason-for-Encounter classification) is the right backbone**: it is patient-oriented, WHO-endorsed, and Indian studies (Odisha ICPC-2/ICPC-3; CMC-Vellore-type family medicine) already use it — so each pick-list item can carry an ICPC-2 code plus a mapping to India's own CBAC, IMNCI and antenatal danger-sign lists. Keep the **English clinical term as a mandatory first-class column** next to the vernacular label.
- The single biggest design/ML risk is **ambiguous idioms of distress** (kamzori, garmi, gas/jalan, chakkar, "tension", safed pani) that map to multiple clinical conditions or to psychosocial distress; the app and the training dataset must **preserve that ambiguity for a clinician to resolve**, never silently collapse it to one diagnosis.

## Key Findings

**1. Indian presenting-complaint data is real but thin, and often diagnosis-based rather than complaint-based.** The most rigorous synthesis available — Bigio, MacLean, Pai et al., *Most common reasons for primary care visits in low- and middle-income countries: A systematic review* (PLOS Global Public Health, 2022; 2(5):e0000196) — screened 22,279 records and **included 17 studies from seven middle-income countries (India contributed 7 of the 17)**, but found that **only four studies (from Brazil, India, Nigeria and South Africa) using ICPC-2 reported both reason-for-encounter and provider-diagnosis data and could be pooled.** It also rated 16 of 17 studies at high risk of bias for national representativeness. Most Indian "morbidity profile" OPD studies report final diagnoses grouped by body system, not the patient's own complaint. A national-general ranking therefore has to triangulate (a) the few ICPC-coded RFE studies, (b) diagnosis-based OPD morbidity studies, and (c) India's programmatic symptom checklists. **This limitation should be stated plainly to anyone using the dataset.**

**2. The recurring top cluster is consistent across regions.** Per Bigio et al. 2022, verbatim: *"The top five RFEs from the four studies were headache, fever, back or low back symptom, cough and pain general/multiple sites."* The Odisha multi-tier study — Pati/Swain group, *Self-reported symptom burden among patients attending public health care facilities in India: Looking through ICPC-3 lens* (PLOS Global Public Health) — interviewed **3,044 patients** across district hospitals, CHCs and PHCs and found that *"65% of the sample reported symptoms as their chief complaint,"* with the commonest reasons being **fever, hypertension, abdominal pain, chest pain, arthritis, skin disease, cough, diabetes, and injury**; the "general" symptom category accounted for 29% and digestive 16%. Peer-reviewed ICPC-2 ranked OPD tables consistently place **R05 Cough, A03 Fever, N01 Headache, D06/D01 abdominal pain, A04 weakness/tiredness, N17 vertigo/dizziness, R07 nasal congestion, D11 diarrhoea** in the top ~15 — the same ordering seen in Indian, Nigerian, Brazilian and Japanese datasets, which is strong corroboration.

**3. Vernacular idioms of distress are core presenting complaints, not fringe cases.** Andrew, Cohen, Salgaonkar & Patel (Sangath/Goa), *The explanatory models of depression and anxiety in primary care: a qualitative study from India* (BMC Res Notes 2012), interviewed 117 primary-care attendees and found *"somatic phenomena were by far the most frequent presenting problems,"* with patients using the construct of **"tension"** to label illness while rejecting a "mental disorder" label. Weaver, *Tension Among Women in North India: An Idiom of Distress and a Cultural Syndrome* (Cult Med Psychiatry 2017;41(1):35–55) documents women using **"tension"** for *"a stunning range of affective and cognitive experiences, including profound sadness and even hints toward suicidal ideation, yet also superficial frustration about small everyday hassles"* — and a 2022 South Asia systematic review (Wahid et al.) links "tension" to suicidal thoughts across multiple studies. For **safed pani** (white vaginal discharge), Kostick, Schensul et al., *Treatment Seeking, Vaginal Discharge and Psychosocial Distress Among Women in Urban Mumbai* (Culture, Medicine and Psychiatry 2010) state it is *"only rarely indicative of a reproductive tract or sexually transmitted infection"* and that *"husbands as problem generators and spousal abusers and women's greater perceived empowerment and reported tension are significantly associated with safed pani."* These are clinical-salience judgments a non-clinical worker should not silently resolve.

**4. India already publishes clean, adoptable pick-lists.** The **CBAC** form (Ayushman Bharat / Ayushman Arogya Mandir, NHSRC/MoHFW) screens everyone ≥30 for NCD/TB symptoms: shortness of breath, cough >2 weeks, blood in sputum, fever >2 weeks, weight loss, night sweats, mouth ulcers/growth, voice change, skin patches, loss of sensation in palm/sole, breast lump, bleeding between periods / after menopause / after intercourse, foul-smelling vaginal discharge. **IMNCI** (WHO–MoHFW, NHM chart booklet) defines general danger signs and four main symptoms (cough/difficult breathing, diarrhoea, fever, ear problem) plus malnutrition and anaemia checks. **Antenatal danger signs** (MoHFW/PMSMA; WHO ANC) are a fixed list: bleeding, severe headache/blurred vision, convulsions, swelling of face/hands, fever, reduced fetal movement, severe abdominal pain, persistent vomiting. **IDSP** P-form syndromes (fever of unknown origin, ARI/ILI, acute diarrhoeal disease, etc.) confirm the same acute-illness core.

## Details

### A. Method and source quality
- **ICPC-2 ranked RFE tables** (R05 Cough, A03 Fever, A04 Weakness/tiredness, N01 Headache, D-codes for abdomen, N17 vertigo, R07 nasal congestion, etc.) are drawn from peer-reviewed primary-care studies using the WONCA/WHO classification; the identical top ranks (R05, A03, N01, D06, A04) appear across Indian, Nigerian, Brazilian and Japanese datasets — corroboration that this cluster is not artefactual.
- **India-specific complaint data**: the Odisha ICPC-3 study (Cuttack, Sambalpur, Nabarangapur; 3,044 patients) and the CMC-Vellore-type family-medicine ICPC study (*International Classification of Primary Care: An Indian Experience*, 47,590 encounters / 59,647 RFEs; RFEs concentrated at chapter level in endocrine 38.6%, cardiovascular 35.9%, respiratory 20.3%, digestive 7.7%, musculoskeletal 6.8% — note this urban, family-physician-run clinic is NCD-heavy). The Swain, Pati & Pati 2017 Odisha ICPC-2 chart review (~2,249 adult records, Bhubaneswar) reported 17 RFEs and explicitly noted the **absence of psychological RFEs**, attributing it to stigma and health-seeking behaviour — an important caveat for any vocabulary.
- **Diagnosis-based OPD morbidity studies** (rural Puducherry [Gupta et al. 2014]; Jaipur/Achrol; north-India urban health centre; Miraj/Maharashtra [Kshirsagar et al. 2019]; Chandigarh urban slum [Kumar et al. 2018]; Madhya Pradesh [Gupta et al. 2015]) consistently show respiratory, musculoskeletal, gastrointestinal, skin, and anaemia/nutritional problems dominating — consistent with the complaint cluster even though they report diagnoses. In one North-India urban centre the top presenting complaints were **body aches and pains (15.6%), cough and cold (10.5%), abdominal pain (8.5%)**.
- **Regional variation** (caveat only): malaria/vector-borne fevers heavier in east/central/tribal belts, Odisha and the Northeast; "heat"/*suudu*-type urinary idioms prominent in Tamil-speaking south; *jhum-jhum* (tingling) syndrome in Garhwal/North; NCD self-reports ("BP"/"sugar") heavier in urban and southern clinics.

### B. Idiom-to-clinical mapping evidence
- **safed pani** ("white water" / leucorrhoea): a leading care-seeking symptom among women; frequently a somatic idiom of psychosocial distress rather than infection (Kostick/Schensul, Mumbai).
- **"tension"**: denotes a wide affective/somatic range including sadness, worry, palpitations and even suicidal ideation — not a synonym for a single diagnosis (Weaver 2017; Andrew/Patel 2012; Wahid 2022).
- **kamzori / sust**: weakness/lethargy; maps to A04 (weakness/tiredness) but overlaps anaemia, hypoglycaemia, post-viral state, depression.
- **chakkar**: dizziness/giddiness/vertigo (N17); ambiguous between true vertigo, pre-syncope, anaemia, hypoglycaemia and BP.
- **gas/acidity, jalan (burning)**: maps to D03 heartburn / D02 epigastric pain / D08 flatulence; *jalan* also = dysuria (urinary burning) or burning feet (neuropathy).
- **ghabrahat**: palpitations/panic (K04) and/or anxiety; ambiguous between cardiac, thyroid and anxiety causes.
- **garmi ("heat")**: culturally salient; can mean fever, urinary burning, rash, or a humoral "hot" state.
- **badan dard**: generalized body ache (A01), often accompanying fever/viral illness.

### C. Standard lists to embed verbatim
- **IMNCI general danger signs**: unable to drink/breastfeed, vomits everything, convulsions, lethargic/unconscious. **Main symptoms**: cough/difficult breathing, diarrhoea, fever, ear problem; **plus** malnutrition and anaemia checks.
- **Antenatal/postnatal danger signs**: vaginal bleeding; severe headache/blurred vision; convulsions/fits; swelling of face and hands; high fever; reduced/absent fetal movements; severe abdominal pain; persistent vomiting; fast/difficult breathing.
- **Adult emergency red flags** (clinical practice + IDSP): chest pain, breathlessness at rest, altered consciousness, severe bleeding, one-sided weakness/stroke signs, severe dehydration, high continuous fever with confusion.

## Final Recommended All-India Presenting-Complaint Vocabulary

Frequency tiers: **VC** = very common, **C** = common, **LI** = less common but important. Idiom column gives Hindi transliteration (Devanagari where useful); major other vernaculars noted where documented.

### Adult general OPD
| # | English clinical term | Vernacular idiom (translit.) | Tier | ICPC-2 | India-programme map | Ambiguity flag |
|---|---|---|---|---|---|---|
|1|Fever|bukhar (बुखार); garmi (heat); Tamil *kaaychal*|VC|A03|IMNCI fever; IDSP PUO/ARI; CBAC fever >2 wk|"garmi" also = urinary burning/rash|
|2|Cough / cold|khansi-zukam (खांसी-ज़ुकाम)|VC|R05|IMNCI cough; CBAC cough >2 wk; IDSP ARI/ILI|Cough >2 wk = TB red flag|
|3|Body ache / generalized pain|badan dard (बदन दर्द)|VC|A01|—|Accompanies fever; also fibromyalgia/depression|
|4|Headache|sir dard (सिर दर्द)|VC|N01|ANC danger sign if severe + blurred vision|Severe + vision change = pre-eclampsia/emergency|
|5|Weakness / tiredness|kamzori (कमज़ोरी), sust|VC|A04|—|Anaemia, hypoglycaemia, depression, post-viral|
|6|Abdominal pain|pet dard (पेट दर्द)|VC|D01 / D06|—|Localized vs general; surgical abdomen = red flag|
|7|Acidity / gas / heartburn|gas, jalan (जलन), khatta|VC|D03 / D02 / D08|—|Epigastric pain can mask cardiac pain|
|8|Diarrhoea / loose motions|dast, patla paikhana|VC|D11|IMNCI diarrhoea; IDSP ADD|Assess dehydration danger signs|
|9|Vomiting / nausea|ulti (उल्टी), ji ghabrana|C|D10 / D09|ANC persistent vomiting|Persistent vomiting in pregnancy = danger|
|10|Dizziness / giddiness|chakkar (चक्कर); Tamil *thalai suttudu*|C|N17|—|Vertigo vs pre-syncope vs anaemia vs BP|
|11|Joint pain / back / neck|jodon ka dard, kamar dard|C|L01 / L02 / L03 / L20|—|Back/neck/low-back coded separately|
|12|Breathlessness|saans phoolna, dam|C|R02|Adult emergency if at rest|Cardiac vs asthma vs anaemia|
|13|Burning urination|peshab me jalan; Tamil *suudu*|C|U01 / U71|—|"jalan" ambiguous; suudu = culture-bound "heat"|
|14|Skin complaint / itching|khujli (खुजली), daane|C|S-chapter|—|Wide differential|
|15|Palpitations / panic|ghabrahat (घबराहट), dhadkan|C|K04|—|Cardiac vs thyroid vs anxiety|
|16|Sore throat|gala kharab / dard|C|R21|IDSP ILI|—|
|17|Nasal congestion / sneezing|naak band, chheenk|C|R07|IDSP ILI|—|
|18|Chest pain|chhati me dard|LI|K02 / A11|Adult emergency red flag|Cardiac until excluded|
|19|Loss of appetite|bhookh na lagna|LI|T03|—|TB / malignancy / depression|
|20|Weight loss|vazan kam hona|LI|T08|CBAC weight loss (TB/Ca)|TB / cancer red flag|
|21|Swelling / oedema|soojan (सूजन)|LI|K07 / S04|ANC swelling danger sign|Renal / cardiac / pre-eclampsia|
|22|"Tension" / worry / unwell|tension, pareshani|C|P01 / P76 (approx.)|—|Broad affective + somatic idiom; do NOT collapse|
|23|Sleep problem|neend na aana|LI|P06|—|—|
|24|Known high BP / on BP meds|BP / High-BP|C|K86|CBAC NCD screen|Self-report ≠ measured; verify|
|25|Known diabetes / "sugar"|sugar / shakkar|C|T90|CBAC NCD screen|Self-report; verify|
|26|Ear pain / discharge|kaan dard / behna|LI|H-chapter|IMNCI ear problem|—|
|27|Eye complaint / vision|aankh / dhundhla dikhna|LI|F-chapter|—|Sudden loss = emergency|
|28|Injury / trauma|chot lagna|C|A80 / L-injury|IDSP injury|Head injury = red flag|
|29|Toothache|daant dard|LI|D19|—|—|
|30|Tingling / numbness|jhunjhuni; jhum-jhum (Garhwal)|LI|N06|—|Neuropathy / diabetes / anxiety|

### Women's / maternal cluster
| # | English clinical term | Idiom (translit.) | Tier | ICPC-2 | Programme map | Ambiguity |
|---|---|---|---|---|---|---|
|31|White vaginal discharge / leucorrhoea|safed pani (सफ़ेद पानी)|VC (women)|X14|CBAC foul discharge|Often psychosocial-distress idiom; rarely RTI/STI|
|32|Menstrual problem|mahwari ki dikkat|C|X-chapter (X05–X09)|—|Abnormal bleeding = CBAC/Ca screen|
|33|Antenatal check / pregnancy|garbhvati janch|C|W78 / W84|ANC schedule|—|
|34|Pregnancy danger signs (set)|khoon, daura, sir dard + dhundhla, soojan|Always|W-codes|MoHFW/PMSMA ANC danger signs|Any one = referral|

### Paediatric cluster (IMNCI)
| # | English clinical term | Idiom (translit.) | Tier | ICPC-2 | Programme map | Ambiguity |
|---|---|---|---|---|---|---|
|35|Child fever|bachche ko bukhar|VC|A03|IMNCI fever|Malaria/dengue in endemic zones|
|36|Cough / difficult breathing|khansi / saans|VC|R05 / R02|IMNCI; pneumonia danger|Fast breathing / indrawing = pneumonia|
|37|Diarrhoea (child)|dast|VC|D11|IMNCI; assess dehydration|Danger signs|
|38|Ear problem|kaan behna|C|H-chapter|IMNCI ear|—|
|39|Malnutrition / not growing|kamzor / dubla|C|T91 / T05|IMNCI malnutrition|—|
|40|Pallor / anaemia|khoon ki kami, peela|C|B80 / B82|IMNCI anaemia|—|

### Always-present danger-sign set (regardless of frequency)
- **Adult emergency**: chest pain, breathlessness at rest, unconsciousness/confusion, severe bleeding, one-sided weakness/stroke signs, severe dehydration, convulsions.
- **Child danger signs (IMNCI)**: unable to drink/feed, vomits everything, convulsions, lethargic/unconscious, fast/difficult breathing, chest indrawing/stridor.
- **Pregnancy danger signs (MoHFW/PMSMA)**: vaginal bleeding; severe headache/blurred vision; convulsions/fits; swelling of face/hands; high fever; reduced fetal movement; severe abdominal pain; persistent vomiting.
- **"Other — please describe" free-text long-tail path** must always be present on every screen.

## Recommendations
1. **Adopt ICPC-2 as the coding backbone now**, and carry an ICPC-3 field for future-proofing (Indian studies are migrating to ICPC-3). Make the **English clinical term a mandatory first-class column** alongside the vernacular label — this is what makes the dataset auditable by doctors and trainable as an idiom→term→code chain.
2. **Ship the ~40-item vocabulary above as v1**, split into adult / maternal / paediatric tabs, with the danger-sign set always visible and an "Other, describe" free-text field on every screen.
3. **Never auto-resolve ambiguous idioms.** For kamzori, chakkar, gas/jalan, garmi, ghabrahat, "tension" and safed pani, present the multiple candidate clinical mappings to the worker and route to the clinician; log the raw idiom verbatim.
4. **Embed the exact CBAC, IMNCI and ANC danger-sign wording** so the app inherits government-validated triage logic and referral thresholds rather than inventing its own.
5. **Staged rollout & thresholds**: pilot in one Hindi-belt PHC + one southern (Tamil/Telugu) PHC; **if >10–15% of encounters fall into "Other, describe,"** mine that free text and promote recurring idioms into the pick-list. **Re-rank the vocabulary every 6–12 months** against the app's own captured frequencies. If a promoted idiom's clinician-confirmed mapping disagrees with the seed mapping in >20% of cases, flag it for re-labelling.

## Caveats
- India-wide, **complaint-level (as opposed to diagnosis-level) data is genuinely thin**; the ranking rests on a small number of non-nationally-representative ICPC studies (Bigio et al. rated 16/17 at high risk of bias for national representativeness) plus diagnosis-based OPD morbidity surveys. Treat the ordering as a well-triangulated estimate, not a validated national census.
- **Psychological complaints are systematically under-recorded** in Indian OPD data (stigma; Swain 2017 found zero psychological RFEs among 17), so the true frequency of "tension"/anxiety/depression presentations is higher than the tables suggest.
- **Regional and seasonal variation is real** (malaria/vector-borne in east/central/tribal belts and monsoon peaks; heat idioms in the south; NCD self-reports in urban/southern clinics) — handled here as caveats, not as the primary structure, per the brief.
- Some **idiom mappings and transliterations** are drawn from clinical/linguistic and consumer-health sources rather than peer-reviewed epidemiology; validate them with field workers before deployment.
- ICPC-2 codes for some India-specific idioms (e.g., "tension" → P01/P76; garmi) are **approximate** and should be confirmed against the official ICPC-2 index during data-schema finalization.

## Dataset suitability notes (for training a small vernacular→clinical→ICPC model)
- **A good label** has three linked fields: (1) verbatim vernacular idiom + transliteration, (2) standardized English clinical term, (3) ICPC-2 code — plus a confidence/ambiguity flag and a frequency tier. This lets the model learn **idiom → term → code** as a chain, with the English term as the human-auditable middle layer.
- **Preserve ambiguity as signal, not noise.** For one-to-many idioms (kamzori → {anaemia, hypoglycaemia, depression, post-viral}; jalan → {heartburn, dysuria, neuropathy}; safed pani → {physiological, RTI, psychosocial distress}), store all candidate mappings with the idiom and label them "requires clinician disambiguation." A model trained to force a single code here would encode unsafe clinical judgments.
- **What would make it robust**: (a) real captured free-text from the app's "Other, describe" field, transcribed and back-mapped; (b) audio/ASR data for spoken vernacular including code-mixing (Hinglish, Tanglish); (c) multi-vernacular parallel entries (Tamil, Telugu, Bengali, Marathi, Odia, Assamese) rather than Hindi-only; (d) negation and duration handling (e.g., "cough >2 weeks" flips to a TB red flag under CBAC); (e) linkage to the eventual clinician diagnosis so the model can learn RFE→diagnosis priors, exactly as the Indian ICPC studies did (e.g., RFE "fever" → malaria/URTI/enteric depending on region and season).

---
### Primary sources
- Bigio J, MacLean E, … Pai M, Adam P. *Most common reasons for primary care visits in low- and middle-income countries: A systematic review.* PLOS Glob Public Health 2022;2(5):e0000196.
- Pati/Swain group. *Self-reported symptom burden among patients attending public health care facilities in India: Looking through ICPC-3 lens.* PLOS Glob Public Health (PMC11073677).
- Swain S, Pati S, Pati S. *A chart review of morbidity patterns among adult patients attending primary care setting in urban Odisha, India: An ICPC experience.* J Family Med Prim Care 2017;6(2):316–322.
- *International Classification of Primary Care: An Indian Experience.* (PMC4311343).
- Andrew G, Cohen A, Salgaonkar S, Patel V. *The explanatory models of depression and anxiety in primary care: a qualitative study from India.* BMC Res Notes 2012;5:499.
- Weaver LJ. *Tension Among Women in North India: An Idiom of Distress and a Cultural Syndrome.* Cult Med Psychiatry 2017;41(1):35–55.
- Kostick K, Schensul SL, et al. *Treatment Seeking, Vaginal Discharge and Psychosocial Distress Among Women in Urban Mumbai.* Cult Med Psychiatry 2010.
- Desai G, Chaturvedi SK. *Idioms of Distress.* J Neurosci Rural Pract 2017;8(Suppl 1):S94–S97.
- MoHFW/NHSRC — *Community Based Assessment Checklist (CBAC)*; NHM — *IMNCI Chart Booklet*; MoHFW/PMSMA — antenatal high-risk/danger-sign guidance; MoHFW — *IDSP* surveillance forms; WHO — *ICPC-2* / WONCA International Classification Committee.