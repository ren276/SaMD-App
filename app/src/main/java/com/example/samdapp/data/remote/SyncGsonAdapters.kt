package com.example.samdapp.data.remote

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import java.time.Instant
import java.time.LocalDate

/** `Instant.toString()`/`LocalDate.toString()` are already ISO-8601 (api-contract.md §6.1's
 *  `client_updated_at`/`date_of_birth` shape), and the backend's `_parse_datetime` accepts any
 *  ISO-8601 offset or `Z` suffix — no custom format string needed, just teach Gson these two
 *  `java.time` types exist. Registered once on the shared Retrofit [com.google.gson.Gson]
 *  (di/NetworkModule.kt) so the batch packer's byte-budget measurement
 *  (SyncBatchPacker.kt) serializes with the exact same adapter the real POST body uses. */
object InstantGsonAdapter : JsonSerializer<Instant>, JsonDeserializer<Instant> {
    override fun serialize(src: Instant, typeOfSrc: java.lang.reflect.Type, context: com.google.gson.JsonSerializationContext) =
        JsonPrimitive(src.toString())

    override fun deserialize(json: com.google.gson.JsonElement, typeOfT: java.lang.reflect.Type, context: com.google.gson.JsonDeserializationContext): Instant =
        Instant.parse(json.asString)
}

/**
 * [deserialize] resolves to null instead of throwing when the value is not a full ISO date.
 *
 * The backend types `AbhaIdentity.date_of_birth` as `str | None` and deliberately allows a bare
 * year such as `"1991"`: ABDM's `profile/account` response carries `yearOfBirth`/`monthOfBirth`/
 * `dayOfBirth` as separate strings, and when only the year is present the adapter emits the year
 * alone rather than a fabricated `"1991-01-01"` (`docs/requirements/abha-internal-contract.md`,
 * decision D3). `LocalDate.parse("1991")` throws `DateTimeParseException`, which is not an
 * `IOException` and so escaped [com.example.samdapp.data.remote.RetrofitAbhaSource]'s error
 * handling entirely and crashed the enrolment coroutine on exactly the accounts D3 exists for.
 *
 * Null is the honest result: a year alone is not a `LocalDate`, and inventing a month and day here
 * would reintroduce the same fabricated precision D3 forbids on the backend. Every consumer
 * already types this field `LocalDate?`. Asking the worker to complete a partial date of birth is
 * a UI job, not a deserializer's.
 */
object LocalDateGsonAdapter : JsonSerializer<LocalDate>, JsonDeserializer<LocalDate> {
    override fun serialize(src: LocalDate, typeOfSrc: java.lang.reflect.Type, context: com.google.gson.JsonSerializationContext) =
        JsonPrimitive(src.toString())

    override fun deserialize(json: com.google.gson.JsonElement, typeOfT: java.lang.reflect.Type, context: com.google.gson.JsonDeserializationContext): LocalDate? =
        runCatching { LocalDate.parse(json.asString) }.getOrNull()
}

/** The one place this [Gson] configuration is built — [com.example.samdapp.di.NetworkModule]'s
 *  real Retrofit instance and [SyncBatchPackerTest]/[SyncRecordMappersTest]-style tests both call
 *  this, so a test asserting the wire shape can never silently drift from what the app actually
 *  sends. */
object SyncGson {
    fun create(): Gson = GsonBuilder()
        .registerTypeAdapter(Instant::class.java, InstantGsonAdapter)
        .registerTypeAdapter(LocalDate::class.java, LocalDateGsonAdapter)
        .create()
}
