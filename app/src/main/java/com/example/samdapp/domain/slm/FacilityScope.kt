package com.example.samdapp.domain.slm

import com.example.samdapp.domain.auth.SessionFacility
import javax.inject.Inject

/** Which facility the clinical data on this device belongs to (on-device SLM memo 7.1, Y-21'). */
sealed interface OwningFacility {
    data class Known(val facilityId: String) : OwningFacility

    data object Unknown : OwningFacility
}

/**
 * The owner of the local clinical data. The store behind it (where the marker lives, its
 * migration, when it is claimed, the wipe-after-drain policy) is the product-wide facility-scope
 * PR's (Y-21'); this PR has no store.
 */
interface LocalDataOwnerFacility {
    suspend fun owningFacility(): OwningFacility
}

/**
 * The staging and prod owner, and it always answers [OwningFacility.Unknown], so the readback seam
 * refuses every request there (Y-21'). Dev overrides it with the session's facility over mock data
 * (R-DEV, `LocalSlmModule`); no other flavor may until the product-wide facility-scope PR.
 */
class UnknownLocalDataOwnerFacility @Inject constructor() : LocalDataOwnerFacility {
    override suspend fun owningFacility(): OwningFacility = OwningFacility.Unknown
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
