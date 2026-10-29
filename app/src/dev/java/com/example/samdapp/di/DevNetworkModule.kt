package com.example.samdapp.di

import com.example.samdapp.BuildConfig
import com.example.samdapp.data.remote.dev.DevDynamicHostInterceptor
import com.example.samdapp.data.remote.dev.DevServerConfig
import com.example.samdapp.data.remote.dev.RealDevServerConfig
import com.example.samdapp.domain.connectivity.BackendHost
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import javax.inject.Singleton

/**
 * Dev-flavor-only network bindings: the dynamic-host interceptor and the config object holding
 * the address it rewrites to. Both classes live in `src/dev/`, so this module and everything it
 * names physically cannot compile into staging or prod — same containment as
 * [DevClinicalMockModule]. `src/staging/` and `src/prod/` bind an empty
 * [com.example.samdapp.di.DevHostInterceptors] list, which is what keeps Hilt from constructing
 * [RealDevServerConfig] (and its SharedPreferences file) in a production build.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DevNetworkModule {

    @Binds @Singleton
    abstract fun bindDevServerConfig(impl: RealDevServerConfig): DevServerConfig

    companion object {
        @Provides
        @DevHostInterceptors
        fun provideDevHostInterceptors(
            interceptor: DevDynamicHostInterceptor,
        ): List<Interceptor> = listOf(interceptor)

        /** The host [DevDynamicHostInterceptor] sends every backend call to: the active URL, or the
         *  built-in one when the active URL does not parse (the interceptor's own fallback). */
        @Provides
        fun provideBackendHost(config: DevServerConfig): BackendHost = BackendHost {
            (config.getActiveBaseUrl().toHttpUrlOrNull() ?: BuildConfig.BACKEND_BASE_URL.toHttpUrl()).host
        }
    }
}
