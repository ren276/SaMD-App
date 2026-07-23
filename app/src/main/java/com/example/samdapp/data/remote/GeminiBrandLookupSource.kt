package com.example.samdapp.data.remote

import com.example.samdapp.BuildConfig
import com.example.samdapp.data.remote.api.GeminiApiService
import com.example.samdapp.data.remote.dto.GeminiContentDto
import com.example.samdapp.data.remote.dto.GeminiGenerationConfigDto
import com.example.samdapp.data.remote.dto.GeminiPartDto
import com.example.samdapp.data.remote.dto.GeminiRequestDto
import com.example.samdapp.data.remote.dto.GeminiThinkingConfigDto
import com.example.samdapp.domain.kernel.BrandLookupSource
import com.example.samdapp.domain.model.IndianBrandSuggestion
import java.util.logging.Logger
import javax.inject.Inject

/**
 * Retrofit-backed implementation of [BrandLookupSource]. Asks Gemini for the single top-selling
 * India-manufactured brand AND its manufacturer for a generic drug name, for display next to
 * [com.example.samdapp.domain.model.EvaluateNlemTreatment.recommendedDrug] on the prescription.
 *
 * Deliberately swallows every failure (missing/blank API key, network error, malformed response)
 * and returns null rather than throwing — this is a best-effort enrichment, never a reason to fail
 * the evaluate pipeline or block the report from rendering.
 */
class GeminiBrandLookupSource @Inject constructor(
    private val geminiApiService: GeminiApiService,
) : BrandLookupSource {

    companion object {
        private val logger = Logger.getLogger("GeminiBrandLookup")
    }

    override suspend fun lookupTopIndianBrand(genericDrugName: String): IndianBrandSuggestion? {
        if (BuildConfig.GEMINI_API_KEY.isBlank()) return null
        return try {
            val prompt = "For the generic drug \"$genericDrugName\", name the top-selling brand sold in " +
                "India by an Indian pharmaceutical company (e.g. Cipla, Sun Pharma, Micro Labs, Abbott " +
                "India, Dr Reddy's). Reply in EXACTLY this format with no extra text, no markdown: " +
                "BrandName | CompanyName"
            val response = geminiApiService.generateContent(
                apiKey = BuildConfig.GEMINI_API_KEY,
                request = GeminiRequestDto(
                    contents = listOf(GeminiContentDto(parts = listOf(GeminiPartDto(text = prompt)))),
                    generationConfig = GeminiGenerationConfigDto(thinkingConfig = GeminiThinkingConfigDto(thinkingBudget = 0)),
                ),
            )
            if (!response.isSuccessful) {
                logger.warning("Gemini brand lookup failed for '$genericDrugName' — HTTP ${response.code()}")
                return null
            }
            val rawText = response.body()
                ?.candidates?.firstOrNull()
                ?.content?.parts?.firstOrNull()
                ?.text?.lineSequence()?.firstOrNull()
                ?.trim()
                ?: return null

            val parts = rawText.split("|").map { it.trim() }
            if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                logger.warning("Gemini brand lookup for '$genericDrugName' returned unparseable text: '$rawText'")
                return null
            }
            IndianBrandSuggestion(brandName = parts[0], companyName = parts[1])
        } catch (e: Exception) {
            logger.warning("Gemini brand lookup unavailable for '$genericDrugName' — ${e.message}")
            null
        }
    }
}
