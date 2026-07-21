package com.example.samdapp.data.mock

import com.example.samdapp.domain.model.AllergyCategory
import com.example.samdapp.domain.model.MedicalHistoryCategory
import com.example.samdapp.domain.model.MedicationKind

/**
 * Investor-demo mock data — one self-consistent, clinically plausible rural-India patient
 * persona used to pre-fill every screen in the app without touching the keyboard.
 *
 * DEMO ONLY — never shipped to production, never stored as real patient data.
 *
 * Persona: Priya Sharma, 34-year-old female from Shivpuri, Madhya Pradesh.
 * Presenting with a 3-day history of fever, productive cough, and mild breathlessness.
 */
object DemoPatientProfile {

    // ── Registration fields ───────────────────────────────────────────────────

    const val FULL_NAME = "Priya Sharma"
    const val DATE_OF_BIRTH = "1991-03-14"          // ISO-8601 (LocalDate.parse-compatible)
    const val BIOLOGICAL_SEX = "Female"
    const val MOBILE_NUMBER = "9826754310"           // 10 digits, MP STD-style prefix
    const val EMERGENCY_CONTACT = "9826000123"
    const val GUARDIAN_OR_SPOUSE_NAME = "Ramesh Sharma"

    const val VILLAGE = "Shivpuri Kalan"
    const val BLOCK = "Shivpuri"
    const val DISTRICT = "Shivpuri"
    const val STATE = "Madhya Pradesh"
    const val PINCODE = "473551"                     // valid Shivpuri-district PIN

    const val CATEGORY = "OBC"
    const val MARITAL_STATUS = "Married"
    const val BLOOD_GROUP = "B+"
    const val AADHAAR_NUMBER = "482937650124"        // 12 digits, fictitious
    const val ABHA_NUMBER = "91234567890123"         // 14 digits, fictitious (ABHA format)
    const val PRIMARY_CARE_CLINIC_NAME = "Shivpuri PHC Block-A"
    const val REFERRING_PHYSICIAN_NAME = "Dr. Meena Tiwari"

    // ── Main concern (replaces "Chief complaint") ─────────────────────────────

    const val MAIN_CONCERN = "High fever since 3 days with cough and difficulty breathing"

    // ── Compounder / Vitals (Step 4) ─────────────────────────────────────────

    // Vitals consistent with moderate respiratory infection in a 34-year-old woman
    const val PULSE_BPM = "77"
    const val BP_SYSTOLIC = "142"
    const val BP_DIASTOLIC = "82"
    const val SPO2_PERCENT = "99"
    const val TEMPERATURE_CELSIUS = "37.0"
    const val RESPIRATORY_RATE = "12"
    const val WEIGHT_KG = "82.2"
    const val HEIGHT_CM = "161.0"
    const val PAIN_SCORE = "6"
    const val BLOOD_GLUCOSE = "110"

    // ── Ailment entry ─────────────────────────────────────────────────────────

    data class DemoAilment(
        val description: String,
        val severity: String,
        val duration: String,
    )

    val AILMENT = DemoAilment(
        description = "Productive cough with yellowish sputum",
        severity = "6",
        duration = "3 days",
    )

    // ── Consultation / History of Present Illness ─────────────────────────────

    const val SYMPTOM_ONSET = "3 days ago — started with chills and body ache"
    const val DURATION_BUCKET = "few_days"
    const val SEVERITY_SCORE = 6
    const val AGGRAVATING_FACTORS = "Lying down, cold air, physical exertion"
    const val RELIEVING_FACTORS = "Paracetamol (partial relief), steam inhalation"
    const val IMPACT_ON_DAILY_ACTIVITIES = "Unable to cook or do household work; fatigue limits standing"
    const val RELEVANT_HISTORY = "Similar episode 8 months ago treated at local PHC with antibiotics"

    // ── Medical & Surgical History ────────────────────────────────────────────

    data class DemoMedicalHistoryItem(
        val category: MedicalHistoryCategory,
        val description: String,
        val yearOrDate: String?,
    )

    val MEDICAL_HISTORY = listOf(
        DemoMedicalHistoryItem(
            category = MedicalHistoryCategory.CHRONIC_CONDITION,
            description = "Mild iron-deficiency anaemia",
            yearOrDate = "2019",
        ),
        DemoMedicalHistoryItem(
            category = MedicalHistoryCategory.HOSPITALIZATION,
            description = "Typhoid — recovered fully, hospitalised for 5 days",
            yearOrDate = "2016",
        ),
    )

    // ── Current Medications ───────────────────────────────────────────────────

    data class DemoMedication(
        val kind: MedicationKind,
        val name: String,
        val dosage: String?,
        val frequency: String?,
    )

    val MEDICATIONS = listOf(
        DemoMedication(
            kind = MedicationKind.MEDICATION,
            name = "Iron + Folic Acid tablet",
            dosage = "60 mg Fe + 500 µg FA",
            frequency = "Once daily after food",
        ),
        DemoMedication(
            kind = MedicationKind.MEDICATION,
            name = "Paracetamol 500 mg",
            dosage = "500 mg",
            frequency = "As needed (SOS) for fever",
        ),
    )

    // ── Allergies ─────────────────────────────────────────────────────────────

    data class DemoAllergy(
        val category: AllergyCategory,
        val allergen: String,
        val reactionType: String?,
    )

    val ALLERGIES = listOf(
        DemoAllergy(
            category = AllergyCategory.DRUG,
            allergen = "Penicillin",
            reactionType = "Skin rash and itching",
        ),
    )

    // ── Family History ────────────────────────────────────────────────────────

    data class DemoFamilyHistoryEntry(val condition: String, val relation: String?)

    val FAMILY_HISTORY = listOf(
        DemoFamilyHistoryEntry("Type 2 Diabetes", "Mother"),
        DemoFamilyHistoryEntry("Hypertension", "Father"),
    )

    // ── Social History ────────────────────────────────────────────────────────

    data class DemoSocialHistory(
        val occupation: String?,
        val tobaccoUse: String?,
        val alcoholUse: String?,
        val recreationalDrugUse: String?,
        val environmentalExposure: String?,
        val recentTravel: String?,
    )

    val SOCIAL_HISTORY = DemoSocialHistory(
        occupation = "Agricultural labourer and homemaker",
        tobaccoUse = "None",
        alcoholUse = "None",
        recreationalDrugUse = "None",
        environmentalExposure = "Seasonal exposure to crop-burning smoke (October–November)",
        recentTravel = "No travel outside district in past 3 months",
    )
}
