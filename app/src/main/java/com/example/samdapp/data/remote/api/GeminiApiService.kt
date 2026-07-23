package com.example.samdapp.data.remote.api

import com.example.samdapp.data.remote.dto.GeminiRequestDto
import com.example.samdapp.data.remote.dto.GeminiResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query

/** Retrofit interface for the Gemini `generateContent` REST endpoint (`generativelanguage.googleapis.com`)
 *  — a distinct, unrelated backend from the SaMDClassifier kernel/evaluate endpoints. */
interface GeminiApiService {

    @POST("v1beta/models/gemini-2.5-flash:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body request: GeminiRequestDto,
    ): Response<GeminiResponseDto>
}
