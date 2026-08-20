package com.example.samdapp.di

import com.example.samdapp.data.kernel.MockKernelFallbackSource
import com.example.samdapp.data.mock.MockVitalsSource
import com.example.samdapp.domain.kernel.KernelFallbackSource
import com.example.samdapp.domain.vitalssource.VitalsSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Dev-flavor-only bindings for the two clinical mock/fallback seams — kernel-mock production
 * safety fix (see `docs/risk-file/kernel-mock-safety.md`). [MockVitalsSource] and
 * [MockKernelFallbackSource] live entirely in `src/dev/`, so this module — and the fabricated
 * data it binds — physically cannot be compiled into a staging or prod build; there is no flag to
 * flip, the classes do not exist outside this source set.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DevClinicalMockModule {

    @Binds @Singleton
    abstract fun bindVitalsSource(impl: MockVitalsSource): VitalsSource

    @Binds @Singleton
    abstract fun bindKernelFallbackSource(impl: MockKernelFallbackSource): KernelFallbackSource
}
