package com.example.samdapp.di

import com.example.samdapp.BuildConfig
import com.example.samdapp.data.vitalssource.DevHubLocalNetworkNeed
import com.example.samdapp.data.vitalssource.HubAssignment
import com.example.samdapp.domain.connectivity.LocalNetworkGate
import com.example.samdapp.domain.vitalssource.HubLocalNetworkNeed
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.HttpUrl.Companion.toHttpUrl

/** The only binding of the optional [HubLocalNetworkNeed] (memo P2-R10). The gateway host is the
 *  host of `PI_GATEWAY_BASE_URL`, parsed through [okhttp3.HttpUrl]. */
@Module
@InstallIn(SingletonComponent::class)
object DevHubLocalNetworkNeedModule {

    @Provides
    fun provideHubLocalNetworkNeed(assignment: HubAssignment, gate: LocalNetworkGate): HubLocalNetworkNeed =
        DevHubLocalNetworkNeed(assignment, gate) { BuildConfig.PI_GATEWAY_BASE_URL.toHttpUrl().host }
}
