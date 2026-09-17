package com.example.samdapp.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor

/**
 * The staging half of the [DevHostInterceptors] seam: no dev-only interceptor, and no dev-only
 * type named anywhere in this source set. `DevDynamicHostInterceptor` and `RealDevServerConfig`
 * do not exist in this build, so Hilt cannot construct them and no `samd_dev_server_config`
 * SharedPreferences file is ever created here.
 */
@Module
@InstallIn(SingletonComponent::class)
object ProductionNetworkModule {

    @Provides
    @DevHostInterceptors
    fun provideDevHostInterceptors(): List<Interceptor> = emptyList()
}
