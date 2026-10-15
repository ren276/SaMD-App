package com.example.samdapp.data.vitalssource.ble

import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import java.util.logging.Logger

/** One companion-device association as the system lists it. [displayName] is for logging only (G1). */
data class Association(val id: Int, val macAddress: String?, val displayName: String?)

/** The dev-only `hub_id` to association ID store (G1). */
interface AssociationIdStore {
    fun get(hubId: String): Int?

    fun put(hubId: String, associationId: Int)

    fun remove(hubId: String)
}

/**
 * Finds a hub's companion-device association by the ID stored when it was created, never by its
 * name (G1, addendum 3.4). No stored ID, or a stored ID the system no longer lists, is null, and the
 * source runs the association dialog again. An association named `SaMD-<hub_id>` under another ID is
 * not this hub's: the name only filters the dialog, the human's choice is the ID.
 */
class HubAssociations(
    private val store: AssociationIdStore,
    private val myAssociations: () -> List<Association>,
    private val disassociate: (Int) -> Unit,
    private val companionDeviceSupported: () -> Boolean,
) {
    /** False on a device without the companion-device feature or its system service: the dialog
     *  cannot run, which the source reports as BLUETOOTH_UNAVAILABLE without launching anything. */
    fun canAssociate(): Boolean = companionDeviceSupported()

    /** `onAssociationCreated`: the human picked this device for [hubId]. */
    fun onCreated(hubId: String, associationId: Int) = store.put(hubId, associationId)

    fun addressFor(hubId: String): String? {
        val id = store.get(hubId) ?: return null
        return myAssociations().firstOrNull { it.id == id }?.macAddress
    }

    /** Q10: after a DIS serial mismatch. The result is already decided, so a system failure here is
     *  logged, never thrown; the stored ID goes either way, so the next Start asks again. */
    fun forget(hubId: String) {
        val id = store.get(hubId)
        store.remove(hubId)
        if (id == null) return
        try {
            disassociate(id)
        } catch (e: Exception) {
            logger.warning("disassociate($id) for $hubId failed: $e")
        }
    }

    companion object {
        private val logger = Logger.getLogger("HubAssociations")

        /** The Android wiring: a private SharedPreferences store and the API 33 calls (3.4). Below 33
         *  the source refuses before any of this is reached (Q1), so the list is simply empty. */
        fun android(context: Context): HubAssociations {
            // Opened on first use, on the acquisition's thread, not at injection.
            val prefs by lazy { context.getSharedPreferences("ble_hub_associations", Context.MODE_PRIVATE) }
            val cdm: CompanionDeviceManager? = context.getSystemService(CompanionDeviceManager::class.java)
            return HubAssociations(
                store = object : AssociationIdStore {
                    override fun get(hubId: String): Int? = if (prefs.contains(hubId)) prefs.getInt(hubId, 0) else null
                    override fun put(hubId: String, associationId: Int) = prefs.edit { putInt(hubId, associationId) }
                    override fun remove(hubId: String) = prefs.edit { remove(hubId) }
                },
                myAssociations = {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || cdm == null) {
                        emptyList()
                    } else {
                        cdm.myAssociations.map { Association(it.id, it.deviceMacAddress?.toString(), it.displayName?.toString()) }
                    }
                },
                disassociate = { id -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) cdm?.disassociate(id) },
                companionDeviceSupported = {
                    context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP) && cdm != null
                },
            )
        }
    }
}
