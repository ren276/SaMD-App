package com.example.samdapp.data.remote.dto

import com.google.gson.annotations.SerializedName
import java.time.Instant

/** Wire shape for `POST /api/v1/sync/push` (api-contract.md §6.1). One record per locally
 *  PENDING row across the twenty syncable tables (MIGRATION_12_13); [table] fixes which
 *  [SyncPayload] subtype [data] holds, but the field is typed `SyncPayload` (not `Any`) only for
 *  compile-time hygiene on the write side — Gson serializes by runtime type either way, and this
 *  DTO is never deserialized. */
data class SyncPushRequestDto(
    @SerializedName("batch_id") val batchId: String,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("client_time") val clientTime: Instant,
    val records: List<SyncRecordDto>,
)

data class SyncRecordDto(
    val table: String,
    /** `upsert` for every table except `audit_log`, which is append-only and always `insert`
     *  (api-contract.md §6.1). */
    val op: String,
    val id: String,
    @SerializedName("client_updated_at") val clientUpdatedAt: Instant,
    @SerializedName("base_version") val baseVersion: Int?,
    val data: SyncPayload,
)

/** Marker for the twenty per-table payload DTOs in SyncPayloadDto.kt — see that file's header
 *  for why each is hand-written rather than reflected off the Room entity. */
interface SyncPayload

data class SyncPushResponseDto(
    @SerializedName("batch_id") val batchId: String,
    val received: Int,
    val applied: Int,
    val stale: Int,
    val conflicted: Int,
    val rejected: Int,
    @SerializedName("server_time") val serverTime: String,
    val results: List<SyncResultDto>,
)

/** [status] is one of `applied`/`stale`/`conflict`/`duplicate`/`rejected` (api-contract.md §6.1's
 *  Android handling rule, applied table-driven in SyncOutboxDrainer). [serverState] is only
 *  present on `conflict` and is never applied locally — see that rule's "not silent clobber". */
data class SyncResultDto(
    val table: String,
    val id: String,
    val status: String,
    @SerializedName("server_version") val serverVersion: Int? = null,
    val code: String? = null,
    val message: String? = null,
    @SerializedName("server_state") val serverState: Map<String, Any?>? = null,
)
