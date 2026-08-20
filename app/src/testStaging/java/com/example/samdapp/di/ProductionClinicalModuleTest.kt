package com.example.samdapp.di

import com.example.samdapp.data.kernel.NoFallbackKernelSource
import com.example.samdapp.data.vitalssource.UnavailableVitalsSource
import com.example.samdapp.domain.model.Attachment
import com.example.samdapp.domain.model.KernelPayload
import com.example.samdapp.domain.model.VitalsReading
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * Runs only under `testStagingDebugUnitTest`. Proves both sides of "no mock kernel scenario and
 * no MockVitalsSource can be bound in staging" (kernel-mock production safety fix,
 * `docs/quality/risk-management-file.md` H-09/H-13):
 *
 * 1. **Compile-time**: `MockVitalsSource` and `MockKernelFallbackSource` live only in
 *    `src/dev/java/`. There is no import of either type anywhere in this file, or reachable from
 *    this staging-flavor test source set — attempting one would fail to compile, since those
 *    classes are not part of the staging compilation unit at all. That absence, not a runtime
 *    flag check, is the actual proof.
 * 2. **Runtime**: `ProductionClinicalModule` (`src/staging/java/.../di/ProductionClinicalModule.kt`)
 *    binds `VitalsSource`/`KernelFallbackSource` to these two honest, non-fabricating
 *    implementations. This test exercises them directly (not through the Dagger graph — the
 *    module's `@Binds` declarations are a 1:1 compile-checked mapping, so testing the bound
 *    implementation's behavior is equivalent to testing what the graph produces).
 */
class ProductionClinicalModuleTest {

    @Test
    fun `staging VitalsSource binding returns an empty reading, never fabricated numbers`() = runTest {
        val source = UnavailableVitalsSource()

        val reading = source.readVitals()

        assertEquals(VitalsReading(), reading)
        assertNull(reading.pulseBpm)
        assertNull(reading.deviceId)
    }

    @Test
    fun `staging KernelFallbackSource binding always returns null, never a fabricated scenario`() = runTest {
        val source = NoFallbackKernelSource()
        val payload = KernelPayload(
            caseToken = "case-1", vitals = VitalsReading(), chiefComplaint = "fever",
            durationBucket = null, severityScore = null, relevantHistory = null,
            transcription = null, attachments = emptyList<Attachment>(),
        )

        val result = source.fallback("case-1", payload, Instant.EPOCH, dataQualityScore = 1.0)

        assertNull(result)
    }
}
