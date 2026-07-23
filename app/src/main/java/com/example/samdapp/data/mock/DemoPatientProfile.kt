package com.example.samdapp.data.mock

import com.example.samdapp.domain.model.AllergyCategory
import com.example.samdapp.domain.model.MedicalHistoryCategory
import com.example.samdapp.domain.model.MedicationKind

/**
 * Investor-demo mock data — 5 self-consistent, clinically plausible rural-India patient
 * personas used to pre-fill every screen in the app without touching the keyboard.
 *
 * DEMO ONLY — never shipped to production, never stored as real patient data.
 *
 * Each persona's vitals AND presenting-complaint phrasing are deliberately picked to match the
 * SaMDClassifier training data (`dataset/canonical_dataset.csv`'s pipe-separated `symptom_string`
 * vocabulary) and vitals-grading thresholds (`thresholds.py`) — so a real `/api/v1/evaluate` call
 * against this data returns a diagnosis/treatment that actually matches the persona's presenting
 * picture, instead of a mismatched one (e.g. a "fever" persona coming back hypertension). Free-text
 * chief complaints ("felt feverish and had a headache") do NOT match the trained vectorizer's
 * vocabulary anywhere near as well as the short pipe-separated clinical phrases used below.
 *
 * [select] switches the active persona for the rest of the session (in-memory only) — call it once
 * from the Register screen's demo-persona picker; every other screen's "Fill demo data" button
 * reads whichever persona is currently [active], so one selection flows through Register →
 * Compounder → Consultation → Medical Background consistently.
 */
object DemoPatientProfile {

    // ── Shared nested shapes (kept as-is so every existing consumer's `List<DemoXxx>` type still
    //    resolves without changes) ─────────────────────────────────────────────

    data class DemoAilment(
        val description: String,
        val severity: String,
        val duration: String,
    )

    data class DemoMedicalHistoryItem(
        val category: MedicalHistoryCategory,
        val description: String,
        val yearOrDate: String?,
    )

    data class DemoMedication(
        val kind: MedicationKind,
        val name: String,
        val dosage: String?,
        val frequency: String?,
    )

    data class DemoAllergy(
        val category: AllergyCategory,
        val allergen: String,
        val reactionType: String?,
    )

    data class DemoFamilyHistoryEntry(val condition: String, val relation: String?)

    data class DemoSocialHistory(
        val occupation: String?,
        val tobaccoUse: String?,
        val alcoholUse: String?,
        val recreationalDrugUse: String?,
        val environmentalExposure: String?,
        val recentTravel: String?,
    )

    /** One complete persona — every field a "Fill demo data" button across the 4 screens reads. */
    data class Persona(
        val label: String,
        // Registration
        val fullName: String,
        val dateOfBirth: String,
        val biologicalSex: String,
        val mobileNumber: String,
        val emergencyContact: String,
        val guardianOrSpouseName: String,
        val village: String,
        val block: String,
        val district: String,
        val state: String,
        val pincode: String,
        val category: String,
        val maritalStatus: String,
        val bloodGroup: String,
        val aadhaarNumber: String,
        val abhaNumber: String,
        val primaryCareClinicName: String,
        val referringPhysicianName: String,
        // Main concern / chief complaint — pipe-separated to match classifier training vocabulary
        val mainConcern: String,
        // Vitals (Compounder / Step 4)
        val pulseBpm: String,
        val bpSystolic: String,
        val bpDiastolic: String,
        val spo2Percent: String,
        val temperatureCelsius: String,
        val respiratoryRate: String,
        val weightKg: String,
        val heightCm: String,
        val painScore: String,
        val bloodGlucose: String,
        // Ailment entry
        val ailment: DemoAilment,
        // Consultation / HPI
        val symptomOnset: String,
        val durationBucket: String,
        val severityScore: Int,
        val aggravatingFactors: String,
        val relievingFactors: String,
        val impactOnDailyActivities: String,
        val relevantHistory: String,
        // Medical background
        val medicalHistory: List<DemoMedicalHistoryItem>,
        val medications: List<DemoMedication>,
        val allergies: List<DemoAllergy>,
        val familyHistory: List<DemoFamilyHistoryEntry>,
        val socialHistory: DemoSocialHistory,
    )

    // ── Persona 1 — Obesity (E66), best-performing classifier class (F1 0.94), routine/low-risk.
    //    BMI 29.4 (80kg/165cm, "obese" tier), all other vitals normal. Metformin/lifestyle-mod
    //    treatment path → brand mapping hits (metformin has Indian brand coverage). ────────────
    private val OBESITY = Persona(
        label = "Suresh Yadav — routine (obesity)",
        fullName = "Suresh Yadav",
        dateOfBirth = "1983-06-20",
        biologicalSex = "Male",
        mobileNumber = "9425110234",
        emergencyContact = "9425110987",
        guardianOrSpouseName = "Kamla Yadav",
        village = "Barwaha",
        block = "Barwaha",
        district = "Barwani",
        state = "Madhya Pradesh",
        pincode = "451115",
        category = "OBC",
        maritalStatus = "Married",
        bloodGroup = "O+",
        aadhaarNumber = "573910284615",
        abhaNumber = "91234500011122",
        primaryCareClinicName = "Barwaha PHC",
        referringPhysicianName = "Dr. Meena Tiwari",
        mainConcern = "Poor sleep | general weakness | exertional dyspnoea | joint pain | fatigue",
        pulseBpm = "78",
        bpSystolic = "128",
        bpDiastolic = "82",
        spo2Percent = "98",
        temperatureCelsius = "36.8",
        respiratoryRate = "16",
        weightKg = "80.0",
        heightCm = "165.0",
        painScore = "3",
        bloodGlucose = "108",
        ailment = DemoAilment(
            description = "Progressive weight gain with joint pain and low exercise tolerance",
            severity = "3",
            duration = "several months",
        ),
        symptomOnset = "Gradual onset over the past 6 months, worsening",
        durationBucket = "months",
        severityScore = 3,
        aggravatingFactors = "Prolonged standing, climbing stairs, physical exertion",
        relievingFactors = "Rest, elevating legs in the evening",
        impactOnDailyActivities = "Tires easily during farm work; avoids long walks",
        relevantHistory = "No prior hospitalisation; weight steadily increased since last pregnancy 6 years ago",
        medicalHistory = listOf(
            DemoMedicalHistoryItem(
                category = MedicalHistoryCategory.CHRONIC_CONDITION,
                description = "Borderline raised blood sugar on last PHC check",
                yearOrDate = "2025",
            ),
        ),
        medications = emptyList(),
        allergies = emptyList(),
        familyHistory = listOf(
            DemoFamilyHistoryEntry("Type 2 Diabetes", "Father"),
            DemoFamilyHistoryEntry("Obesity", "Sister"),
        ),
        socialHistory = DemoSocialHistory(
            occupation = "Farmer (own land, cotton and soybean)",
            tobaccoUse = "None",
            alcoholUse = "Occasional, social",
            recreationalDrugUse = "None",
            environmentalExposure = "Pesticide spraying during sowing season",
            recentTravel = "No travel outside district in past 3 months",
        ),
    )

    // ── Persona 2 — Essential hypertension (I10), F1 0.93, brand-mapped (amlodipine/telmisartan).
    //    BP 148/94 = adult grade1 ("monitor" banner) — moderate, not urgent. ───────────────────
    private val HYPERTENSION = Persona(
        label = "Kamla Devi — moderate (hypertension)",
        fullName = "Kamla Devi",
        dateOfBirth = "1970-11-02",
        biologicalSex = "Female",
        mobileNumber = "9868245310",
        emergencyContact = "9868245098",
        guardianOrSpouseName = "Mohan Lal",
        village = "Semaria",
        block = "Huzur",
        district = "Rewa",
        state = "Madhya Pradesh",
        pincode = "486001",
        category = "General",
        maritalStatus = "Married",
        bloodGroup = "A+",
        aadhaarNumber = "664521398710",
        abhaNumber = "91234500022233",
        primaryCareClinicName = "Rewa District PHC",
        referringPhysicianName = "Dr. Meena Tiwari",
        mainConcern = "Headache | dizziness | blurred vision | epistaxis | general weakness | poor sleep",
        pulseBpm = "84",
        bpSystolic = "148",
        bpDiastolic = "94",
        spo2Percent = "97",
        temperatureCelsius = "36.9",
        respiratoryRate = "15",
        weightKg = "68.0",
        heightCm = "158.0",
        painScore = "4",
        bloodGlucose = "118",
        ailment = DemoAilment(
            description = "Recurrent headache with dizziness and occasional nosebleeds",
            severity = "4",
            duration = "3 weeks",
        ),
        symptomOnset = "3 weeks ago, gradually worsening, worse in the mornings",
        durationBucket = "weeks",
        severityScore = 4,
        aggravatingFactors = "Stress, salty food, hot weather",
        relievingFactors = "Rest in a dark room, tea",
        impactOnDailyActivities = "Skips household chores on bad days due to dizziness",
        relevantHistory = "Diagnosed with high blood pressure 2 years ago, irregular follow-up",
        medicalHistory = listOf(
            DemoMedicalHistoryItem(
                category = MedicalHistoryCategory.CHRONIC_CONDITION,
                description = "Essential hypertension",
                yearOrDate = "2023",
            ),
        ),
        medications = listOf(
            DemoMedication(
                kind = MedicationKind.MEDICATION,
                name = "Amlodipine 5 mg",
                dosage = "5 mg",
                frequency = "Once daily, morning",
            ),
        ),
        allergies = emptyList(),
        familyHistory = listOf(
            DemoFamilyHistoryEntry("Hypertension", "Mother"),
            DemoFamilyHistoryEntry("Stroke", "Father"),
        ),
        socialHistory = DemoSocialHistory(
            occupation = "Homemaker",
            tobaccoUse = "None",
            alcoholUse = "None",
            recreationalDrugUse = "None",
            environmentalExposure = "Indoor cooking smoke (wood-fired chulha)",
            recentTravel = "No travel outside district in past 3 months",
        ),
    )

    // ── Persona 3 — Typhoid (A01.0), F1 0.88, brand-mapped (azithromycin). Fixes the reported
    //    bug directly: a "fever" persona needs fever-specific phrasing (stepladder fever/rose
    //    spots/GI symptoms), not just "fever", to disambiguate from other classes. ─────────────
    private val TYPHOID = Persona(
        label = "Anita Kumari — moderate (typhoid / fever)",
        fullName = "Anita Kumari",
        dateOfBirth = "1999-08-15",
        biologicalSex = "Female",
        mobileNumber = "9741023856",
        emergencyContact = "9741023011",
        guardianOrSpouseName = "Ram Prasad (father)",
        village = "Ichhawar",
        block = "Ichhawar",
        district = "Sehore",
        state = "Madhya Pradesh",
        pincode = "466115",
        category = "SC",
        maritalStatus = "Unmarried",
        bloodGroup = "B+",
        aadhaarNumber = "748263910452",
        abhaNumber = "91234500033344",
        primaryCareClinicName = "Ichhawar Community Health Centre",
        referringPhysicianName = "Dr. Meena Tiwari",
        mainConcern = "Stepladder fever | general weakness | rose spots | constipation or diarrhoea | loss of appetite | abdominal pain",
        pulseBpm = "102",
        bpSystolic = "110",
        bpDiastolic = "72",
        spo2Percent = "97",
        temperatureCelsius = "39.2",
        respiratoryRate = "20",
        weightKg = "55.0",
        heightCm = "160.0",
        painScore = "5",
        bloodGlucose = "92",
        ailment = DemoAilment(
            description = "Stepwise rising fever with abdominal discomfort and loss of appetite",
            severity = "6",
            duration = "4 days",
        ),
        symptomOnset = "4 days ago — started with low-grade fever, now stepwise rising each evening",
        durationBucket = "few_days",
        severityScore = 6,
        aggravatingFactors = "Evenings, physical exertion",
        relievingFactors = "Paracetamol (partial relief)",
        impactOnDailyActivities = "Unable to attend college; staying in bed most of the day",
        relevantHistory = "No similar episode before; drinks well water at home",
        medicalHistory = emptyList(),
        medications = listOf(
            DemoMedication(
                kind = MedicationKind.MEDICATION,
                name = "Paracetamol 500 mg",
                dosage = "500 mg",
                frequency = "As needed (SOS) for fever",
            ),
        ),
        allergies = emptyList(),
        familyHistory = listOf(
            DemoFamilyHistoryEntry("None known", null),
        ),
        socialHistory = DemoSocialHistory(
            occupation = "College student",
            tobaccoUse = "None",
            alcoholUse = "None",
            recreationalDrugUse = "None",
            environmentalExposure = "Untreated well water at home",
            recentTravel = "No travel outside district in past 3 months",
        ),
    )

    // ── Persona 4 — Dengue with warning signs (A91; sibling classes A90/B54 also clinically
    //    acceptable given this class's weak F1 0.38). SpO2 84 (severe-hypoxemia) drives
    //    urgent-review regardless of exact label. No brand mapping (referral/IV-fluids case). ──
    private val DENGUE = Persona(
        label = "Deepak Chouhan — urgent (dengue warning signs)",
        fullName = "Deepak Chouhan",
        dateOfBirth = "1991-02-10",
        biologicalSex = "Male",
        mobileNumber = "9926410573",
        emergencyContact = "9926410099",
        guardianOrSpouseName = "Sarita Chouhan",
        village = "Bhikangaon",
        block = "Bhikangaon",
        district = "Khargone",
        state = "Madhya Pradesh",
        pincode = "451335",
        category = "OBC",
        maritalStatus = "Married",
        bloodGroup = "AB+",
        aadhaarNumber = "839217465032",
        abhaNumber = "91234500044455",
        primaryCareClinicName = "Bhikangaon PHC",
        referringPhysicianName = "Dr. Meena Tiwari",
        mainConcern = "Headache | bleeding gums | high fever | fatigue | breathlessness | severe breathlessness | abdominal pain",
        pulseBpm = "126",
        bpSystolic = "100",
        bpDiastolic = "64",
        spo2Percent = "84",
        temperatureCelsius = "39.8",
        respiratoryRate = "26",
        weightKg = "70.0",
        heightCm = "172.0",
        painScore = "8",
        bloodGlucose = "95",
        ailment = DemoAilment(
            description = "High fever with bleeding gums and worsening breathlessness",
            severity = "8",
            duration = "5 days",
        ),
        symptomOnset = "5 days ago — high fever from day 1, bleeding gums and breathlessness from day 4",
        durationBucket = "few_days",
        severityScore = 8,
        aggravatingFactors = "Any exertion, lying flat",
        relievingFactors = "Sitting upright, sips of ORS",
        impactOnDailyActivities = "Unable to work; bedridden since yesterday, family very worried",
        relevantHistory = "No known bleeding disorder; two other cases reported in same village this week",
        medicalHistory = emptyList(),
        medications = listOf(
            DemoMedication(
                kind = MedicationKind.MEDICATION,
                name = "Paracetamol 500 mg",
                dosage = "500 mg",
                frequency = "As needed (SOS) for fever — avoiding NSAIDs given bleeding gums",
            ),
        ),
        allergies = emptyList(),
        familyHistory = listOf(
            DemoFamilyHistoryEntry("None known", null),
        ),
        socialHistory = DemoSocialHistory(
            occupation = "Daily-wage construction labourer",
            tobaccoUse = "Occasional bidi",
            alcoholUse = "None",
            recreationalDrugUse = "None",
            environmentalExposure = "Standing water near work site (monsoon season)",
            recentTravel = "No travel outside district in past 3 months",
        ),
    )

    // ── Persona 5 — Pediatric UTI (N39.0), F1 0.91, no brand mapping (Fosfomycin/Nitrofurantoin).
    //    age<18 → pediatric_referral_flag and requiresHumanReview are hard invariants regardless
    //    of the resolved condition, so this persona demonstrates that path with otherwise
    //    low/moderate-severity vitals. ──────────────────────────────────────────────────────────
    private val PEDIATRIC_UTI = Persona(
        label = "Kavya Sharma — pediatric referral (UTI)",
        fullName = "Kavya Sharma",
        dateOfBirth = "2015-04-18",
        biologicalSex = "Female",
        mobileNumber = "9758013426",
        emergencyContact = "9758013000",
        guardianOrSpouseName = "Sunita Sharma (mother)",
        village = "Hatta",
        block = "Hatta",
        district = "Damoh",
        state = "Madhya Pradesh",
        pincode = "470661",
        category = "General",
        maritalStatus = "Unmarried",
        bloodGroup = "B+",
        aadhaarNumber = "927104638251",
        abhaNumber = "91234500055566",
        primaryCareClinicName = "Hatta PHC",
        referringPhysicianName = "Dr. Meena Tiwari",
        mainConcern = "Burning micturition | lower abdominal pain | fever | increased urinary frequency | general weakness",
        pulseBpm = "92",
        bpSystolic = "105",
        bpDiastolic = "68",
        spo2Percent = "98",
        temperatureCelsius = "38.3",
        respiratoryRate = "18",
        weightKg = "32.0",
        heightCm = "138.0",
        painScore = "4",
        bloodGlucose = "90",
        ailment = DemoAilment(
            description = "Burning sensation on urination with lower abdominal pain and mild fever",
            severity = "4",
            duration = "2 days",
        ),
        symptomOnset = "2 days ago — noticed crying while urinating, mild fever since yesterday",
        durationBucket = "few_days",
        severityScore = 4,
        aggravatingFactors = "Urination, drinking less water",
        relievingFactors = "Drinking more water, rest",
        impactOnDailyActivities = "Missed school for 2 days, cranky and low appetite",
        relevantHistory = "No prior urinary complaints; reported by mother, not the child directly",
        medicalHistory = emptyList(),
        medications = emptyList(),
        allergies = emptyList(),
        familyHistory = listOf(
            DemoFamilyHistoryEntry("None known", null),
        ),
        socialHistory = DemoSocialHistory(
            occupation = "Student (Class 6)",
            tobaccoUse = null,
            alcoholUse = null,
            recreationalDrugUse = null,
            environmentalExposure = "Shared school toilet with poor hygiene (per mother)",
            recentTravel = "No travel outside district in past 3 months",
        ),
    )

    val PERSONAS: List<Persona> = listOf(OBESITY, HYPERTENSION, TYPHOID, DENGUE, PEDIATRIC_UTI)

    private var activeIndex = 0

    val active: Persona get() = PERSONAS[activeIndex]

    /** Switches the active persona for the rest of the session (in-memory only). Call once from
     *  the Register screen's persona picker — every later screen's "Fill demo data" button reads
     *  [active], so the selection flows through the whole intake pipeline consistently. */
    fun select(index: Int) {
        activeIndex = index.coerceIn(0, PERSONAS.lastIndex)
    }

    // ── Facade: every property below preserves the exact name the 4 existing "fillDemoData()"
    //    ViewModels already read, so none of those call sites need to change. ──────────────────

    val FULL_NAME get() = active.fullName
    val DATE_OF_BIRTH get() = active.dateOfBirth
    val BIOLOGICAL_SEX get() = active.biologicalSex
    val MOBILE_NUMBER get() = active.mobileNumber
    val EMERGENCY_CONTACT get() = active.emergencyContact
    val GUARDIAN_OR_SPOUSE_NAME get() = active.guardianOrSpouseName

    val VILLAGE get() = active.village
    val BLOCK get() = active.block
    val DISTRICT get() = active.district
    val STATE get() = active.state
    val PINCODE get() = active.pincode

    val CATEGORY get() = active.category
    val MARITAL_STATUS get() = active.maritalStatus
    val BLOOD_GROUP get() = active.bloodGroup
    val AADHAAR_NUMBER get() = active.aadhaarNumber
    val ABHA_NUMBER get() = active.abhaNumber
    val PRIMARY_CARE_CLINIC_NAME get() = active.primaryCareClinicName
    val REFERRING_PHYSICIAN_NAME get() = active.referringPhysicianName

    val MAIN_CONCERN get() = active.mainConcern

    val PULSE_BPM get() = active.pulseBpm
    val BP_SYSTOLIC get() = active.bpSystolic
    val BP_DIASTOLIC get() = active.bpDiastolic
    val SPO2_PERCENT get() = active.spo2Percent
    val TEMPERATURE_CELSIUS get() = active.temperatureCelsius
    val RESPIRATORY_RATE get() = active.respiratoryRate
    val WEIGHT_KG get() = active.weightKg
    val HEIGHT_CM get() = active.heightCm
    val PAIN_SCORE get() = active.painScore
    val BLOOD_GLUCOSE get() = active.bloodGlucose

    val AILMENT get() = active.ailment

    val SYMPTOM_ONSET get() = active.symptomOnset
    val DURATION_BUCKET get() = active.durationBucket
    val SEVERITY_SCORE get() = active.severityScore
    val AGGRAVATING_FACTORS get() = active.aggravatingFactors
    val RELIEVING_FACTORS get() = active.relievingFactors
    val IMPACT_ON_DAILY_ACTIVITIES get() = active.impactOnDailyActivities
    val RELEVANT_HISTORY get() = active.relevantHistory

    val MEDICAL_HISTORY get() = active.medicalHistory
    val MEDICATIONS get() = active.medications
    val ALLERGIES get() = active.allergies
    val FAMILY_HISTORY get() = active.familyHistory
    val SOCIAL_HISTORY get() = active.socialHistory
}
