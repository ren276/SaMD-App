package com.example.samdapp.di

import android.Manifest
import com.example.samdapp.domain.connectivity.NearbyDevicesMembers
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet

/** The Bluetooth members of the NEARBY_DEVICES group (memo P2-R2a). Dev is the only flavor that
 *  declares any of them, and this file is the only place they are named for that purpose. */
@Module
@InstallIn(SingletonComponent::class)
object DevNearbyDevicesModule {

    @Provides
    @ElementsIntoSet
    @NearbyDevicesMembers
    fun provideBluetoothNearbyDevicesMembers(): Set<String> = setOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
    )
}
