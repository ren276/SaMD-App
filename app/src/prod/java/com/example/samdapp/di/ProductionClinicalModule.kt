package com.example.samdapp.di

import com.example.samdapp.data.kernel.NoFallbackKernelSource
import com.example.samdapp.data.vitalssource.UnavailableVitalsSource
import com.example.samdapp.domain.kernel.KernelFallbackSource
import com.example.samdapp.domain.vitalssource.VitalsSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Staging/prod bindings for the two clinical mock/fallback seams — kernel-mock production safety
 * fix (see `docs/risk-file/kernel-mock-safety.md`). No fabricated vitals, no fabricated kernel
 * scenario: [UnavailableVitalsSource] returns an empty reading, [NoFallbackKernelSource] always
 * returns null, which `GenerateKernelReportUseCase` turns into an honest UNAVAILABLE result. Dev
 * binds the mock implementations instead (`src/dev/java/.../di/DevClinicalMockModule.kt`).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProductionClinicalModule {

    @Binds @Singleton
    abstract fun bindVitalsSource(impl: UnavailableVitalsSource): VitalsSource

    @Binds @Singleton
    abstract fun bindKernelFallbackSource(impl: NoFallbackKernelSource): KernelFallbackSource
}
