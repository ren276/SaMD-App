package com.example.samdapp.di

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Memo P2-R2a, dev half: main's two members plus dev's three Bluetooth members, exactly. */
class DevNearbyDevicesSetTest {

    @Test
    fun `the dev set is exactly the five NEARBY_DEVICES members`() {
        assertEquals(
            setOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.UWB_RANGING,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            ),
            NearbyDevicesModule.provideMainNearbyDevicesMembers() + DevNearbyDevicesModule.provideBluetoothNearbyDevicesMembers(),
        )
    }
}
