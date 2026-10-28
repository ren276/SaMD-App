package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * The facility that owns this phone's local data (facility-scope memo 3.1, FS-3). At most one row:
 * every write goes through `DeviceDataOwnerDao.claim`, which always uses [OWNER_ROW_ID], so a
 * second owner is a primary-key conflict, never a second row. In the same encrypted database as
 * the data it describes, so a Keystore-loss reset removes both together.
 *
 * [legacy] is true when the owner was claimed over data already on the phone at the upgrade from
 * schema 22 (FS-4(a)). MIGRATION_22_23.
 */
@Entity(tableName = "device_data_owner")
data class DeviceDataOwnerEntity(
    @PrimaryKey val id: Int = OWNER_ROW_ID,
    val facilityId: String,
    val facilityName: String,
    val claimedAt: Instant,
    val claimedByWorkerId: String,
    val legacy: Boolean,
) {
    companion object {
        const val OWNER_ROW_ID = 1
    }
}
