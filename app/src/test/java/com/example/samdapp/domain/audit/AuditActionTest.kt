package com.example.samdapp.domain.audit

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ASR track PR 2 (`scratchpad/asr-field-audit-memo.md` B.4). Pins the four `VOICE_FIELD_*`
 * wire values against the backend mirror's parser (`test_audit_actions_device.py`), which reads
 * these constructor arguments directly out of this file. Nothing emits these actions yet.
 */
class AuditActionTest {

    @Test
    fun voiceFieldActions_haveTheWireValuesTheMemoSpecifies() {
        assertEquals("voice_field_suggested", AuditAction.VOICE_FIELD_SUGGESTED.value)
        assertEquals("voice_field_confirmed", AuditAction.VOICE_FIELD_CONFIRMED.value)
        assertEquals("voice_field_edited", AuditAction.VOICE_FIELD_EDITED.value)
        assertEquals("voice_field_rejected", AuditAction.VOICE_FIELD_REJECTED.value)
    }
}
