package com.example.samdapp.domain.kernel

import com.example.samdapp.domain.model.IndianBrandSuggestion

/**
 * Domain boundary for looking up the top India-manufactured brand (name + manufacturer) for a
 * generic drug — mirrors the "named mock boundary" pattern used by [RemoteKernelSource]/[EvaluateKernelSource].
 *
 * Implementations:
 * - [com.example.samdapp.data.remote.GeminiBrandLookupSource] — calls the Gemini API.
 *
 * Never throws — a lookup failure (network down, no API key configured, malformed response) is
 * swallowed and returns null. Brand lookup is a best-effort enrichment on the prescription, not a
 * critical-path dependency; the drug name/dosage still print without it.
 */
interface BrandLookupSource {
    suspend fun lookupTopIndianBrand(genericDrugName: String): IndianBrandSuggestion?
}
