package com.example.samdapp.data.config

import android.content.Context
import android.provider.Settings
import com.example.samdapp.BuildConfig
import com.example.samdapp.domain.config.DeviceInfoProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidDeviceInfoProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceInfoProvider {
    override fun deviceId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"

    override fun softwareVersion(): String = BuildConfig.VERSION_NAME
}
