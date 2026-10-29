package com.example.samdapp.di

import com.example.samdapp.domain.connectivity.MAIN_NEARBY_DEVICES_MEMBERS
import com.example.samdapp.domain.connectivity.NearbyDevicesMembers
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet

/** Every flavor's share of the NEARBY_DEVICES member set (memo P2-R2a). Staging and prod add
 *  nothing to it; dev adds its own members in `src/dev`. */
@Module
@InstallIn(SingletonComponent::class)
object NearbyDevicesModule {

    @Provides
    @ElementsIntoSet
    @NearbyDevicesMembers
    fun provideMainNearbyDevicesMembers(): Set<String> = MAIN_NEARBY_DEVICES_MEMBERS
}
