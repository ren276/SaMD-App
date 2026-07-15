package com.example.samdapp.domain.audit

import org.junit.Assert.assertEquals
import org.junit.Test

/** REQ-AUD-01: payload JSON shape (keys preserved, nulls emitted as JSON null). */
class AuditPayloadTest {

    @Test
    fun `builds json object from field pairs`() {
        val json = auditPayload("fullName" to "Asha", "biologicalSex" to "Female")
        assertEquals("""{"fullName":"Asha","biologicalSex":"Female"}""", json)
    }

    @Test
    fun `null values are emitted as json null`() {
        val json = auditPayload("uri" to "file://x", "relation" to null)
        assertEquals("""{"uri":"file://x","relation":null}""", json)
    }

    @Test
    fun `empty produces empty object`() {
        assertEquals("{}", auditPayload())
    }
}
