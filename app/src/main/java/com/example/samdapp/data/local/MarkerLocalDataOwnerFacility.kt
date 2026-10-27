package com.example.samdapp.data.local

import com.example.samdapp.data.local.dao.DeviceDataOwnerDao
import com.example.samdapp.domain.slm.LocalDataOwnerFacility
import com.example.samdapp.domain.slm.OwningFacility
import javax.inject.Inject

/**
 * The owner of the local data is the `device_data_owner` marker (facility-scope memo 3.6, FS-7),
 * in every flavor: claimed at sign-in, so a session of another facility never exists over this
 * data (3.2). No marker, or one with a blank id, is [OwningFacility.Unknown] and the readback seam
 * refuses. The id is what is compared, never the name (`facilityScopeAllows`).
 */
class MarkerLocalDataOwnerFacility @Inject constructor(
    private val ownerDao: DeviceDataOwnerDao,
) : LocalDataOwnerFacility {
    override suspend fun owningFacility(): OwningFacility =
        ownerDao.read()?.facilityId?.takeIf { it.isNotBlank() }?.let { OwningFacility.Known(it) } ?: OwningFacility.Unknown
}
