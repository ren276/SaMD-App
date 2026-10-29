package com.example.samdapp.data.connectivity

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.samdapp.domain.connectivity.AppPermissions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** [AppPermissions] read from this package: the requested list is the running flavor's merged
 *  manifest, and every grant is read at call time, never cached. */
class PackageAppPermissions @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppPermissions {

    override fun requested(): Set<String> {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }
        return info.requestedPermissions?.toSet().orEmpty()
    }

    override fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
