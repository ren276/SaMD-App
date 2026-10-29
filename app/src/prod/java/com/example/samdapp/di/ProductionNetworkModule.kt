package com.example.samdapp.di

import com.example.samdapp.BuildConfig
import com.example.samdapp.domain.connectivity.BackendHost
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor

/**
 * The prod half of the [DevHostInterceptors] seam: no dev-only interceptor, and no dev-only
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

    /** No host rewrite in this build, so every backend call reaches the built-in URL's host. */
    @Provides
    fun provideBackendHost(): BackendHost = BackendHost { BuildConfig.BACKEND_BASE_URL.toHttpUrl().host }
}
