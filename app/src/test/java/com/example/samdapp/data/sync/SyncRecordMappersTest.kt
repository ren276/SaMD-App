package com.example.samdapp.data.sync

import com.example.samdapp.data.local.entity.AilmentEntity
import com.example.samdapp.data.local.entity.AttachmentEntity
import com.example.samdapp.data.local.entity.ObservationEntity
import com.example.samdapp.data.remote.SyncGson
import com.example.samdapp.data.remote.dto.AilmentSyncPayloadDto
import com.example.samdapp.data.remote.dto.AttachmentSyncPayloadDto
import com.example.samdapp.domain.model.AttachmentType
import com.example.samdapp.domain.model.MeasurementType
import com.example.samdapp.domain.model.ObservationSource
import com.example.samdapp.domain.model.ObservationType
import com.example.samdapp.domain.model.Visibility
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The forbidden-field rules from api-contract.md §6.1 apply on the device side too — SAMD-SYNC-
 *  6006 for `audio_local_uri`, and `synced_to_cloud_at` is server-stamped (TableSpec.server_owned
 *  in backend/core/app/services/sync.py), so sending either back is a wasted, rejected round
 *  trip. Asserts against the *serialized* wire JSON (not just the DTO's Kotlin shape), since a
 *  field that's absent from the DTO class is the thing that actually protects the boundary. */
class SyncRecordMappersTest {

    private val gson = SyncGson.create()

    @Test
    fun `ailment payload excludes audioLocalUri and syncedToCloudAt`() {
        val ailment = AilmentEntity(
            id = "a1", patientId = "p1", encounterId = "e1", description = "cough",
            measurementType = MeasurementType.NON_MEASURABLE, visibility = Visibility.PUBLIC,
            measuredValue = null, measuredUnit = null, severity = 3, onset = null, duration = null,
            qualifiers = null, audioLocalUri = "content://private/audio.m4a",
            capturedAtOffline = Instant.EPOCH, syncedToCloudAt = Instant.EPOCH, deletedAt = null,
            createdAt = Instant.EPOCH, localModifiedAt = Instant.EPOCH,
        )

        val payload = ailment.toSyncRecord().data as AilmentSyncPayloadDto
        val json = JsonParser.parseString(gson.toJson(payload)).asJsonObject

        assertFalse(json.has("audio_local_uri"))
        assertFalse(json.has("synced_to_cloud_at"))
        // Sanity: this isn't an empty-payload false positive — ordinary fields are still there.
        assertTrue(json.has("description"))
        assertEquals("cough", json.get("description").asString)
    }

    @Test
    fun `PRIVATE-visibility ailments still sync clinical text, only the audio is excluded`() {
        val ailment = AilmentEntity(
            id = "a2", patientId = "p1", encounterId = "e1", description = "private symptom",
            measurementType = MeasurementType.NON_MEASURABLE, visibility = Visibility.PRIVATE,
            measuredValue = null, measuredUnit = null, severity = null, onset = null, duration = null,
            qualifiers = null, audioLocalUri = "content://private/audio.m4a",
            capturedAtOffline = Instant.EPOCH, syncedToCloudAt = null, deletedAt = null,
            createdAt = Instant.EPOCH, localModifiedAt = Instant.EPOCH,
        )

        val record = ailment.toSyncRecord()
        val payload = record.data as AilmentSyncPayloadDto
        val json = JsonParser.parseString(gson.toJson(payload)).asJsonObject

        assertEquals("PRIVATE", payload.visibility)
        assertEquals("private symptom", json.get("description").asString)
        assertFalse(json.has("audio_local_uri"))
    }

    @Test
    fun `observation payload excludes syncedToCloudAt`() {
        val observation = ObservationEntity(
            id = "o1", patientId = "p1", encounterId = "e1", type = ObservationType.PULSE,
            valueNumeric = 72.0, valueText = null, unit = "bpm", deviceId = null,
            source = ObservationSource.MANUAL, captureMethod = null, recordedAt = Instant.EPOCH,
            syncedToCloudAt = Instant.EPOCH, createdAt = Instant.EPOCH, localModifiedAt = Instant.EPOCH,
        )

        val json = JsonParser.parseString(gson.toJson(observation.toSyncRecord().data)).asJsonObject

        assertFalse(json.has("synced_to_cloud_at"))
        assertTrue(json.has("value_numeric"))
    }

    @Test
    fun `attachment uri is sent under the wire key uri, not renamed to local_uri`() {
        // backend/core/app/services/sync.py's TableSpec aliases "uri" -> its own local_uri
        // column; sending the wire key local_uri directly would be rejected as unrecognized.
        val attachment = AttachmentEntity(
            id = "att1", consultationId = "c1", type = AttachmentType.IMAGE,
            uri = "content://media/external/images/media/42",
            createdAt = Instant.EPOCH, localModifiedAt = Instant.EPOCH,
        )

        val record = attachment.toSyncRecord()
        val payload = record.data as AttachmentSyncPayloadDto
        val json = JsonParser.parseString(gson.toJson(payload)).asJsonObject

        assertEquals("content://media/external/images/media/42", payload.uri)
        assertTrue(json.has("uri"))
        assertFalse(json.has("local_uri"))
    }

    @Test
    fun `client_updated_at and base_version map from localModifiedAt and serverVersion`() {
        val attachment = AttachmentEntity(
            id = "att2", consultationId = "c1", type = AttachmentType.IMAGE, uri = "content://x",
            createdAt = Instant.EPOCH, serverVersion = 7,
            localModifiedAt = Instant.parse("2026-08-16T09:41:30Z"),
        )

        val record = attachment.toSyncRecord()

        assertEquals(Instant.parse("2026-08-16T09:41:30Z"), record.clientUpdatedAt)
        assertEquals(7, record.baseVersion)
        assertEquals("upsert", record.op)
        assertEquals("attachments", record.table)
    }
}
