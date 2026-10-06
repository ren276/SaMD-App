package com.example.samdapp.di

import com.example.samdapp.BuildConfig
import com.example.samdapp.data.kernel.MockKernelFallbackSource
import com.example.samdapp.data.kernel.NoFallbackKernelSource
import com.example.samdapp.data.vitalssource.PiGatewayVitalsSource
import com.example.samdapp.domain.kernel.KernelFallbackSource
import com.example.samdapp.domain.vitalssource.VitalsSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Dev-flavor-only bindings for the two clinical mock/fallback seams, kernel-mock production
 * safety fix (see `docs/risk-file/kernel-mock-safety.md`). [PiGatewayVitalsSource] and
 * [MockKernelFallbackSource] live entirely in `src/dev/`, so this module, and the fabricated or
 * accessory-sourced data it binds, physically cannot be compiled into a staging or prod build;
 * there is no flag to flip, the classes do not exist outside this source set.
 *
 * The [VitalsSource] binding moved from `MockVitalsSource` to [PiGatewayVitalsSource] when the Pi
 * instrument gateway landed. `MockVitalsSource` is deliberately left in `src/dev/` unbound rather
 * than deleted: it is the hardware-free path, and swapping this one line back is how a demo runs
 * with no Pi on the network.
 *
 * The [KernelFallbackSource] binding is selected by `samd.dev.kernelFallback` in local.properties
 * (see [devKernelFallbackMode]). Absent keeps the mock; `none` binds [NoFallbackKernelSource], the
 * same always-null source staging and prod use, so the honest UNAVAILABLE path can be exercised
 * live on a dev build.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DevClinicalMockModule {

    @Binds @Singleton
    abstract fun bindVitalsSource(impl: PiGatewayVitalsSource): VitalsSource

    companion object {
        /** [Provider]s, so only the selected source is ever constructed. */
        @Provides @Singleton
        fun provideKernelFallbackSource(
            mock: Provider<MockKernelFallbackSource>,
            none: Provider<NoFallbackKernelSource>,
        ): KernelFallbackSource = when (devKernelFallbackMode(BuildConfig.DEV_KERNEL_FALLBACK)) {
            DevKernelFallbackMode.MOCK -> mock.get()
            DevKernelFallbackMode.NONE -> none.get()
        }
    }
}
