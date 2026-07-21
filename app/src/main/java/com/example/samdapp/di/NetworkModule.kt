package com.example.samdapp.di

import android.util.Log
import com.example.samdapp.data.remote.RetrofitKernelSource
import com.example.samdapp.data.remote.api.KernelApiService
import com.example.samdapp.domain.kernel.RemoteKernelSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Provides the Retrofit + OkHttp stack for the local FastAPI kernel endpoint.
 *
 * Base URL: `http://10.203.3.29:8000/` — LAN IP of host machine running the FastAPI kernel,
 * for physical-device testing over Wi-Fi. Requires `android:usesCleartextTraffic="true"` in the manifest
 * (plain HTTP, not TLS — acceptable for a local dev/demo server; production would use HTTPS
 * behind an internal VPN or a real backend URL).
 *
 * Timeouts are deliberately conservative (connect 10s, read/write 30s) to give the ML
 * inference time to complete while still surfacing failures quickly enough for the graceful
 * fallback in [GenerateKernelReportUseCase] to kick in.
 *
 * [NetworkModule.Bindings] handles @Binds (abstract methods) since Hilt requires them to live
 * in an abstract class/interface, not a companion object.
 */
@Module(
    includes = [NetworkModule.Bindings::class],
)
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val KERNEL_BASE_URL = "http://10.203.3.29:8000/"
    private const val TAG = "KernelNetwork"

    @Provides
    @Singleton
    fun provideHttpLoggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor { message -> Log.d(TAG, message) }.apply {
            // BODY level in debug builds for full request/response tracing;
            // would be NONE in a production build via BuildConfig flag.
            level = HttpLoggingInterceptor.Level.BODY
        }

    @Provides
    @Singleton
    fun provideOkHttpClient(loggingInterceptor: HttpLoggingInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(KERNEL_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Provides
    @Singleton
    fun provideKernelApiService(retrofit: Retrofit): KernelApiService =
        retrofit.create(KernelApiService::class.java)

    /** Separate abstract class to host @Binds methods (Hilt requirement). */
    @Module
    @InstallIn(SingletonComponent::class)
    abstract class Bindings {
        @Binds
        @Singleton
        abstract fun bindRemoteKernelSource(impl: RetrofitKernelSource): RemoteKernelSource
    }
}
