package com.example.samdapp.data.sync

import com.example.samdapp.domain.model.BackendConstraintMessages
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `BackendConstraintMessages` copies four strings out of `_SQLSTATE_MESSAGES` in
 * `backend/core/app/services/sync.py`, because the backend gives a duplicate ABHA and a missing
 * required field the same error code and only the message tells them apart.
 *
 * That is a coupling, and this is the guard on it. Same technique and same limits as
 * `SyncDaoSqlContractTest` and `AuditActionBackendMirrorTest`: it reads the Python as text, so it
 * cannot prove the backend still SENDS these, only that the strings this app matches on are
 * still the ones that module writes. A crude guard that runs beats a precise one that does not.
 *
 * If this fails, the fix is to copy the backend's new wording here, not to delete the constant:
 * without it, every duplicate silently reads as a generic rejection and the one cause this whole
 * sequence started from stops being separable.
 */
class SyncFailureMessageMirrorTest {

    private val backendSync: File = File(System.getProperty("user.dir")!!).let { cwd ->
        generateSequence(cwd) { it.parentFile }
            .map { File(it, "backend/core/app/services/sync.py") }
            .firstOrNull { it.isFile }
            ?: error("could not locate backend/core/app/services/sync.py from $cwd")
    }

    @Test
    fun `every constraint message this app matches on is still the one the backend writes`() {
        val source = backendSync.readText()
        listOf(
            "23505 / duplicate ABHA" to BackendConstraintMessages.UNIQUE_VIOLATION,
            "23503 / parent missing" to BackendConstraintMessages.FOREIGN_KEY_VIOLATION,
            "23502 / required field" to BackendConstraintMessages.NOT_NULL_VIOLATION,
            "23514 / invalid value" to BackendConstraintMessages.CHECK_VIOLATION,
        ).forEach { (label, message) ->
            assertTrue(
                "$label: the device matches on \"$message\" and backend/core/app/services/sync.py " +
                    "no longer contains that string. A duplicate would now read as a generic " +
                    "rejection and the worker would be told the wrong thing.",
                source.contains("\"$message\""),
            )
        }
    }

    @Test
    fun `the backend still classifies a unique violation as a rejection the device sees as FAILED`() {
        // The duplicate only reaches the failed list because 23505 is CONFLICT, which
        // toLocalSyncState maps to FAILED. If the backend reclassified it RETRYABLE, the
        // duplicate would retry five times and then arrive as RETRIES_EXHAUSTED instead, and the
        // worker would be told to press a button that cannot work.
        val source = backendSync.readText()
        assertTrue(
            "23505 is no longer classified CONFLICT backend-side; SyncFailureReason.DUPLICATE_RECORD " +
                "may no longer be reachable.",
            Regex("""["']23505["']\s*:\s*SyncRetryClass\.CONFLICT""").containsMatchIn(source),
        )
    }
}
