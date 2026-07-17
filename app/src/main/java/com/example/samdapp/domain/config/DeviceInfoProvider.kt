package com.example.samdapp.domain.config

/** Tablet/device identity + app build identity written into every kernel report (Part A schema
 *  addendum) — captured for the report artifact even though this app never displays it in any
 *  worker-facing UI. See [com.example.samdapp.data.config.AndroidDeviceInfoProvider]. */
interface DeviceInfoProvider {
    fun deviceId(): String
    fun softwareVersion(): String
}
