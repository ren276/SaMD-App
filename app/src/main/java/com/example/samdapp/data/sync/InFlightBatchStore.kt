package com.example.samdapp.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** [localModifiedAtEpochMilli] is the row's `localModifiedAt` at the moment this member was
 *  packed into the batch — the immutable "sent revision" that survives a crash/resume so
 *  [RoomSyncOutboxRepository.applyAck]'s guarded UPDATE still compares against what was actually
 *  sent, not whatever the row's `localModifiedAt` happens to be when the resumed ack lands. */
@Serializable
data class InFlightBatchMember(val table: String, val id: String, val localModifiedAtEpochMilli: Long)

@Serializable
data class InFlightBatch(val batchId: String, val members: List<InFlightBatchMember>)

/**
 * Crash-safety for the sync outbox — the subtlest correctness point in Phase 6b. The batch_id a
 * batch is about to send under, plus which (table, id) rows it contains, is persisted here
 * *before* the network call, and cleared only after that batch's ack is recorded locally. If the
 * worker dies between "backend applied" and "device recorded ack" (process kill, connectivity
 * loss), the next run finds this non-empty, reuses the *same* batch_id to resend the *same* rows
 * (SyncOutboxDrainer.resumeInFlightBatch), and the backend's 24h idempotency replay
 * (api-contract.md §6.1) returns the original stored response verbatim rather than re-applying —
 * so the row ends up applied exactly once, not twice under two different batch_ids.
 *
 * A separate DataStore file from "auth_session" (DataStoreAuthTokenStore.kt): unrelated concern,
 * no reason to couple their on-disk lifetimes.
 */
interface InFlightBatchStore {
    /** [Result.success] with `null` means genuinely no in-flight batch. [Result.failure] means
     *  the persisted batch_id/member list could not be read (DataStore IOException, or JSON that
     *  fails to decode) — the caller MUST treat this as "unknown, do not proceed" rather than
     *  "none": silently treating an unreadable in-flight batch as no batch would let a fresh
     *  batch_id get minted for rows the backend may have already applied under the lost batch_id,
     *  double-applying an append-only `audit_log` insert. */
    suspend fun load(): Result<InFlightBatch?>
    suspend fun save(batch: InFlightBatch)
    suspend fun clear()
}

private val Context.syncOutboxDataStore: DataStore<Preferences> by preferencesDataStore(name = "sync_outbox")

private object Keys {
    val IN_FLIGHT_BATCH_JSON = stringPreferencesKey("in_flight_batch_json")
    val OWNER_MISMATCH_HOLD = booleanPreferencesKey("owner_mismatch_hold")
}

@Singleton
class DataStoreInFlightBatchStore @Inject constructor(
    @ApplicationContext context: Context,
) : InFlightBatchStore {
    private val dataStore = context.syncOutboxDataStore

    override suspend fun load(): Result<InFlightBatch?> {
        val prefs = try {
            dataStore.data.first()
        } catch (e: IOException) {
            return Result.failure(e)
        }
        val json = prefs[Keys.IN_FLIGHT_BATCH_JSON] ?: return Result.success(null)
        return try {
            Result.success(Json.decodeFromString(InFlightBatch.serializer(), json))
        } catch (e: SerializationException) {
            Result.failure(e)
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        }
    }

    override suspend fun save(batch: InFlightBatch) {
        val json = Json.encodeToString(InFlightBatch.serializer(), batch)
        dataStore.edit { prefs -> prefs[Keys.IN_FLIGHT_BATCH_JSON] = json }
    }

    override suspend fun clear() {
        dataStore.edit { prefs -> prefs.remove(Keys.IN_FLIGHT_BATCH_JSON) }
    }
}

/**
 * Set when the server refused a batch because this phone's data belongs to another facility than
 * the session (`SAMD-SYNC-6007`, facility-scope memo 3.5). While it is set the drainer pushes
 * nothing: resending the same batch under the same session is refused identically, so it is never
 * retried automatically. Released only by a new sign-in (`BackendAuthSession`). Kept in the
 * `sync_outbox` DataStore so it survives process death, like the in-flight batch it holds back.
 */
interface OwnerMismatchHold {
    suspend fun isHeld(): Boolean
    suspend fun hold()
    suspend fun release()
}

@Singleton
class DataStoreOwnerMismatchHold @Inject constructor(
    @ApplicationContext context: Context,
) : OwnerMismatchHold {
    private val dataStore = context.syncOutboxDataStore

    override suspend fun isHeld(): Boolean = dataStore.data.first()[Keys.OWNER_MISMATCH_HOLD] == true

    override suspend fun hold() {
        dataStore.edit { it[Keys.OWNER_MISMATCH_HOLD] = true }
    }

    override suspend fun release() {
        dataStore.edit { it.remove(Keys.OWNER_MISMATCH_HOLD) }
    }
}

/** In memory, for tests and for drainer constructions that do not exercise the hold. */
class MemoryOwnerMismatchHold : OwnerMismatchHold {
    @Volatile private var held = false
    override suspend fun isHeld() = held
    override suspend fun hold() { held = true }
    override suspend fun release() { held = false }
}
