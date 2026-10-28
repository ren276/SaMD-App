package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.samdapp.data.local.entity.DeviceDataOwnerEntity
import com.example.samdapp.data.local.entity.DeviceDataOwnerEntity.Companion.OWNER_ROW_ID
import java.time.Instant

/** The owner marker (facility-scope memo 3.1). Claim and read only: nothing in the app clears it
 *  (FS-R2); moving a phone clears the app's storage (memo section 12). */
@Dao
interface DeviceDataOwnerDao {

    /**
     * Writes the owner only when there is none, and says whether this call wrote it. Atomic: one
     * `INSERT OR IGNORE` on the fixed key, so of two concurrent claims exactly one wins and the
     * loser changes nothing. A read-then-insert would let both win.
     */
    suspend fun claim(
        facilityId: String,
        facilityName: String,
        claimedAt: Instant,
        claimedByWorkerId: String,
        legacy: Boolean,
    ): Boolean = insertIfAbsent(
        DeviceDataOwnerEntity(
            id = OWNER_ROW_ID,
            facilityId = facilityId,
            facilityName = facilityName,
            claimedAt = claimedAt,
            claimedByWorkerId = claimedByWorkerId,
            legacy = legacy,
        ),
    ) != -1L

    /** Use [claim]. Returns the new rowid, or -1 when an owner already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(owner: DeviceDataOwnerEntity): Long

    @Query("SELECT * FROM device_data_owner WHERE id = $OWNER_ROW_ID")
    suspend fun read(): DeviceDataOwnerEntity?
}
