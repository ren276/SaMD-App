package com.example.samdapp.di

import com.example.samdapp.data.kernel.MockKernelFallbackSource
import com.example.samdapp.data.vitalssource.PiGatewayVitalsSource
import com.example.samdapp.domain.kernel.KernelFallbackSource
import com.example.samdapp.domain.vitalssource.VitalsSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Dev-flavor-only bindings for the two clinical mock/fallback seams — kernel-mock production
 * safety fix (see `docs/risk-file/kernel-mock-safety.md`). [PiGatewayVitalsSource] and
 * [MockKernelFallbackSource] live entirely in `src/dev/`, so this module, and the fabricated or
 * accessory-sourced data it binds, physically cannot be compiled into a staging or prod build;
 * there is no flag to flip, the classes do not exist outside this source set.
 *
 * The [VitalsSource] binding moved from `MockVitalsSource` to [PiGatewayVitalsSource] when the Pi
 * instrument gateway landed. `MockVitalsSource` is deliberately left in `src/dev/` unbound rather
 * than deleted: it is the hardware-free path, and swapping this one line back is how a demo runs
 * with no Pi on the network.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DevClinicalMockModule {

    @Binds @Singleton
    abstract fun bindVitalsSource(impl: PiGatewayVitalsSource): VitalsSource

    @Binds @Singleton
    abstract fun bindKernelFallbackSource(impl: MockKernelFallbackSource): KernelFallbackSource
}
