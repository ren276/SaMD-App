package com.example.samdapp.data.remote.dto

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaluateReportDtoTest {

    private val gson = Gson()

    private val richJson = """
        {
          "diagnostic_summary": {
            "primary_icd_candidate": "A09",
            "primary_ailment_name": "Acute gastroenteritis",
            "differential": [
              {
                "icd_candidate": "A09",
                "adjusted_confidence": 0.82,
                "original_symptom_confidence": 0.75,
                "vitals_tier_alignment": 0.9,
                "why": "Vitals consistent with mild dehydration tier"
              },
              {
                "icd_candidate": "A00",
                "adjusted_confidence": 0.11,
                "original_symptom_confidence": 0.15,
                "vitals_tier_alignment": 0.4,
                "why": "Cholera less likely given normothermia"
              }
            ]
          },
          "nlem_treatment": {
            "recommendedDrug": "Oral Rehydration Salts (ORS)",
            "levelOfHealthcare": ["PHC", "SC"],
            "availableAtPHC": true,
            "dosageForms": ["Sachet", "Solution"],
            "pediatricDose": "One sachet in 1L water, ad-lib",
            "citation": {
              "source": "NLEM 2022",
              "page": 12,
              "section": "Gastrointestinal",
              "subsection": "Rehydration",
              "item_num": "3.1.2"
            },
            "confidence": "high",
            "referralReason": null,
            "matchedDisease": {
              "icd_candidate": "A09",
              "disease_name": "Acute gastroenteritis"
            }
          },
          "brand_mapping": {
            "generic_name": "Oral Rehydration Salts",
            "jan_aushadhi_brand": "Jan Aushadhi ORS",
            "commercial_brands": ["Electral", "ORSL"],
            "brand_mapping_available": true
          },
          "safety_and_triage": {
            "vitals_triage": {
              "bp_grade": "Normal", "pulse": "Normal", "respiratory_rate": "Normal",
              "spo2": "Normal", "temperature": "Normal", "bmi": "Normal",
              "glucose": "Normal", "overall_urgency": "Low"
            },
            "requiresHumanReview": false,
            "pediatric_referral_flag": false,
            "failure_reason": null
          }
        }
    """.trimIndent()

    private val edgeJson = """
        {
          "diagnostic_summary": {
            "primary_icd_candidate": null,
            "primary_ailment_name": null,
            "differential": []
          },
          "nlem_treatment": {
            "recommendedDrug": null,
            "levelOfHealthcare": null,
            "availableAtPHC": null,
            "dosageForms": [],
            "pediatricDose": null,
            "citation": null,
            "confidence": null,
            "referralReason": "Escalate to PHC: safety-net triggered",
            "matchedDisease": null
          },
          "brand_mapping": null,
          "safety_and_triage": {
            "vitals_triage": null,
            "requiresHumanReview": true,
            "pediatric_referral_flag": false,
            "failure_reason": "Safety-net triggered: vitals indicate high-risk tier"
          }
        }
    """.trimIndent()

    @Test
    fun `fully populated response deserializes every mixed-case field correctly`() {
        val dto = gson.fromJson(richJson, EvaluateReportDto::class.java)

        assertEquals("A09", dto.diagnosticSummary.primaryIcdCandidate)
        assertEquals(2, dto.diagnosticSummary.differential.size)
        assertEquals("A09", dto.diagnosticSummary.differential[0].icdCandidate)
        assertEquals(0.82, dto.diagnosticSummary.differential[0].adjustedConfidence, 0.0001)

        assertEquals("Oral Rehydration Salts (ORS)", dto.nlemTreatment.recommendedDrug)
        assertEquals(listOf("PHC", "SC"), dto.nlemTreatment.levelOfHealthcare)
        assertEquals(true, dto.nlemTreatment.availableAtPHC)
        assertEquals(listOf("Sachet", "Solution"), dto.nlemTreatment.dosageForms)
        assertEquals(12, dto.nlemTreatment.citation?.page)
        assertEquals("Acute gastroenteritis", dto.nlemTreatment.matchedDisease?.diseaseName)

        assertEquals(listOf("Electral", "ORSL"), dto.brandMapping?.commercialBrands)
        assertEquals(true, dto.brandMapping?.brandMappingAvailable)

        assertEquals("Normal", dto.safetyAndTriage.vitalsTriage?.bpGrade)
        assertEquals(false, dto.safetyAndTriage.requiresHumanReview)
    }

    @Test
    fun `nullable objects, empty lists, and safety-net fields deserialize correctly`() {
        val dto = gson.fromJson(edgeJson, EvaluateReportDto::class.java)

        assertNull(dto.diagnosticSummary.primaryIcdCandidate)
        assertTrue(dto.diagnosticSummary.differential.isEmpty())

        assertNull(dto.nlemTreatment.citation)
        assertNull(dto.nlemTreatment.matchedDisease)
        assertTrue(dto.nlemTreatment.dosageForms.isEmpty())
        assertEquals("Escalate to PHC: safety-net triggered", dto.nlemTreatment.referralReason)

        assertNull(dto.brandMapping)

        assertNull(dto.safetyAndTriage.vitalsTriage)
        assertEquals(true, dto.safetyAndTriage.requiresHumanReview)
        assertEquals(
            "Safety-net triggered: vitals indicate high-risk tier",
            dto.safetyAndTriage.failureReason,
        )
    }
}
