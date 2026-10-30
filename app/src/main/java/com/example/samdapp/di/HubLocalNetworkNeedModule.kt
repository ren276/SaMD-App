package com.example.samdapp.di

import com.example.samdapp.domain.vitalssource.HubLocalNetworkNeed
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Declares [HubLocalNetworkNeed] as optional (memo P2-R10): `src/dev` binds an implementation;
 *  staging and prod bind nothing, and an absent binding means no request. */
@Module
@InstallIn(SingletonComponent::class)
abstract class HubLocalNetworkNeedModule {
    @BindsOptionalOf
    abstract fun optionalHubLocalNetworkNeed(): HubLocalNetworkNeed
}
