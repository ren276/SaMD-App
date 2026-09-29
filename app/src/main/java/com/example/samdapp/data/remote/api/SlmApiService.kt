package com.example.samdapp.data.remote.api

import com.example.samdapp.data.remote.dto.ApiEnvelopeDto
import com.example.samdapp.data.remote.dto.SlmReadbackRequestDto
import com.example.samdapp.data.remote.dto.SlmReadbackResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit interface for the backend's SLM readback proxy (`POST /api/v1/slm/readback`,
 * api-contract.md §11.3), which forwards to the generation service's `POST /v1/generate`; that
 * forwarding is the backend's job and is invisible from here.
 *
 * **The device never calls the generation service.** Topology (A), permanently: clinical narrative
 * gets exactly one egress point, the one that already carries `/api/v1/assess`, so there is one
 * credential on the device, one reachability model on a rural link, one audit chain and one circuit
 * breaker. A second base URL here would be a second of each.
 *
 * Returns [Response] rather than the envelope directly, like [KernelApiService] and
 * [AuthApiService], so the binding can read the RFC 9457 problem document out of a non-2xx body and
 * branch on its `SAMD-*` code. Declaring the bare envelope would raise an `HttpException` whose
 * message is only the status line, and the code in the body would be discarded unread, which is the
 * defect `RetrofitKernelSource`'s KDoc records having had to undo.
 *
 * **This service rides a client derived from the shared backend one**, not a fifth client of its
 * own, so it carries the same `BearerInterceptor` and `TokenAuthenticator` every other backend call
 * carries. See the Hilt module that provides it.
 */
interface SlmApiService {

    @POST("api/v1/slm/readback")
    suspend fun readback(
        @Body request: SlmReadbackRequestDto,
    ): Response<ApiEnvelopeDto<SlmReadbackResponseDto>>
}
