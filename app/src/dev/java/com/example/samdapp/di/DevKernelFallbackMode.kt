package com.example.samdapp.di

/**
 * Which [com.example.samdapp.domain.kernel.KernelFallbackSource] a dev build binds, read from
 * `samd.dev.kernelFallback` in local.properties (surfaced as `BuildConfig.DEV_KERNEL_FALLBACK`).
 *
 * Dev normally binds the mock scenario source, which means the honest UNAVAILABLE card and its
 * Retry affordance never appear on a dev build: every failed assessment becomes a mock result.
 * Staging points at a placeholder URL, so before this switch those states had no live coverage
 * at all. [NONE] binds the same always-null source staging and prod bind.
 */
enum class DevKernelFallbackMode {
    /** Today's dev behaviour: `MockKernelFallbackSource`. */
    MOCK,

    /** `NoFallbackKernelSource`, exactly as staging and prod. */
    NONE,
}

/** Absent (blank) keeps [DevKernelFallbackMode.MOCK]. An unrecognised value throws rather than
 *  quietly keeping the mock, because a tester who believes the mock is off and is shown a mock
 *  result is the confusion this switch exists to remove. */
fun devKernelFallbackMode(raw: String): DevKernelFallbackMode = when (raw.trim().lowercase()) {
    "", "mock" -> DevKernelFallbackMode.MOCK
    "none" -> DevKernelFallbackMode.NONE
    else -> throw IllegalArgumentException(
        "samd.dev.kernelFallback must be 'mock' or 'none' (or absent), got '$raw'",
    )
}
