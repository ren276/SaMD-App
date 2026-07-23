package com.example.samdapp.data.remote.dto

/** Minimal request/response shapes for the Gemini `generateContent` REST API — only the fields
 *  this app's single prompt-in/text-out use actually needs. */
data class GeminiRequestDto(
    val contents: List<GeminiContentDto>,
    val generationConfig: GeminiGenerationConfigDto? = null,
)

/** `thinkingBudget: 0` disables Gemini 2.5's extended "thinking" — for a one-word brand-name
 *  extraction it only adds latency (measured ~5.6s with thinking vs ~0.7s without) with no
 *  quality benefit, and risked timing out on a real device network. */
data class GeminiGenerationConfigDto(
    val thinkingConfig: GeminiThinkingConfigDto,
)

data class GeminiThinkingConfigDto(
    val thinkingBudget: Int,
)

data class GeminiContentDto(
    val parts: List<GeminiPartDto>,
)

data class GeminiPartDto(
    val text: String,
)

data class GeminiResponseDto(
    val candidates: List<GeminiCandidateDto>? = null,
)

data class GeminiCandidateDto(
    val content: GeminiContentDto? = null,
)
