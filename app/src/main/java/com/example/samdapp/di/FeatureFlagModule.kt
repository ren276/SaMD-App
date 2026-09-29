package com.example.samdapp.di

import com.example.samdapp.config.FeatureFlags
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier

/**
 * Marks [com.example.samdapp.config.FeatureFlags.SLM_READBACK_ENABLED] where it is injected rather
 * than read inline.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SlmReadbackEnabled

/**
 * The one feature flag this app injects instead of reading from the object directly, and the
 * reason it is the one.
 *
 * Every other flag is read inline at its use site, which is fine for a flag whose guard is a
 * rendering decision: a test can look at the constant and at the composable and see that a control
 * is not drawn. `SLM_READBACK_ENABLED` guards something different. It is the only thing standing
 * between a shipped build and a clinical generation path, and the claim that has to hold is not
 * "the button is not drawn" but "the use case has no reachable caller".
 *
 * **A `const val` cannot be moved at runtime, so a guard that reads it inline cannot be tested from
 * both sides.** A test written against the shipped `false` passes identically whether the guard
 * checks the flag, checks something else, or is `if (false)`. It reports the property satisfied
 * while proving nothing about the thing it names, which is this project's characteristic bug and is
 * exactly what the eleven-instance standing rule is about. MEASURED, not hypothetical: the first
 * version of this guard covered only the action that opens the sheet and left the action that
 * starts a generation ungated, and the test that caught it could only catch it because the flag was
 * injectable by then.
 *
 * So the value stays in [FeatureFlags], which remains the single place a reader looks up what is on
 * and why, and this module is the seam that lets a test supply the other value. Deliberately not
 * generalised to the other flags: they have no such test pressure today and a module that provided
 * eleven booleans would be scaffolding for a need nobody has.
 */
@Module
@InstallIn(SingletonComponent::class)
object FeatureFlagModule {

    @Provides
    @SlmReadbackEnabled
    fun provideSlmReadbackEnabled(): Boolean = FeatureFlags.SLM_READBACK_ENABLED
}
