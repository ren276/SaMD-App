package com.example.samdapp.domain.slm

import com.example.samdapp.domain.auth.SessionFacility

/** Which facility the clinical data on this device belongs to (on-device SLM memo 7.1, Y-21'). */
sealed interface OwningFacility {
    data class Known(val facilityId: String) : OwningFacility

    data object Unknown : OwningFacility
}

/**
 * The owner of the local clinical data. One implementation in every flavor, the
 * `device_data_owner` marker (`MarkerLocalDataOwnerFacility`, facility-scope memo 3.6, FS-7), which
 * replaced the always-UNKNOWN staging and prod owner and the dev session-facility override (R-DEV).
 */
interface LocalDataOwnerFacility {
    suspend fun owningFacility(): OwningFacility
}

/**
 * Y-21' (memo 7.1): a readback is in scope only when the signed-in session's facility and the
 * facility owning the local data are both known and are the same facility, compared by id and
 * never by name. Everything else refuses: fail closed.
 */
internal fun facilityScopeAllows(session: SessionFacility?, owner: OwningFacility): Boolean {
    if (session == null || session.facilityId.isBlank()) return false
    if (owner !is OwningFacility.Known || owner.facilityId.isBlank()) return false
    return session.facilityId == owner.facilityId
}

/** The `INPUT_REFUSED` audit reason for a facility-scope refusal (Y-21'). */
internal const val FACILITY_SCOPE_AUDIT_REASON = "facility_scope"
