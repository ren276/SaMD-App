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

object LocalDateGsonAdapter : JsonSerializer<LocalDate>, JsonDeserializer<LocalDate> {
    override fun serialize(src: LocalDate, typeOfSrc: java.lang.reflect.Type, context: com.google.gson.JsonSerializationContext) =
        JsonPrimitive(src.toString())

    override fun deserialize(json: com.google.gson.JsonElement, typeOfT: java.lang.reflect.Type, context: com.google.gson.JsonDeserializationContext): LocalDate =
        LocalDate.parse(json.asString)
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
