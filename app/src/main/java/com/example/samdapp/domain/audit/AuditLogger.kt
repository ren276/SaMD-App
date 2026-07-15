package com.example.samdapp.domain.audit

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface AuditLogger {
    suspend fun log(action: String, patientId: String? = null, caseRecordId: String? = null, payload: String)
}

/** Builds the JSON blob stored in AuditLogEntity.payload from a flat set of fields. */
fun auditPayload(vararg fields: Pair<String, String?>): String =
    buildJsonObject { fields.forEach { (key, value) -> put(key, value) } }.toString()
