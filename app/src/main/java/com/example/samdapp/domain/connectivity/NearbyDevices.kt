package com.example.samdapp.domain.connectivity

import android.Manifest
import javax.inject.Qualifier

/**
 * The platform NEARBY_DEVICES group members, as a Hilt `Set<String>` multibinding (memo P2-R2a).
 * Written out rather than read from `PermissionInfo.group`, which is not relied on for platform
 * runtime groups. A granted member makes the local-network grant silent, so the rationale is
 * skipped then. `src/main` contributes the two below; the Bluetooth members are contributed from
 * `src/dev` only, the one flavor that declares them (G-B11b keeps their names out of this tree).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NearbyDevicesMembers

val MAIN_NEARBY_DEVICES_MEMBERS: Set<String> = setOf(
    Manifest.permission.NEARBY_WIFI_DEVICES,
    Manifest.permission.UWB_RANGING,
)

/** What this package declares in its merged manifest and what it holds now. A seam, so the
 *  rationale decision runs on the JVM. */
interface AppPermissions {
    fun requested(): Set<String>
    fun isGranted(permission: String): Boolean
}

/** True when one of [members] that this build declares is granted. [ACCESS_LOCAL_NETWORK_PERMISSION]
 *  itself never counts, whatever [members] holds. */
fun nearbyDevicesMemberGranted(members: Set<String>, permissions: AppPermissions): Boolean =
    (members - ACCESS_LOCAL_NETWORK_PERMISSION).intersect(permissions.requested()).any(permissions::isGranted)
