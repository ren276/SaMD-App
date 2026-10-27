package com.example.samdapp.di

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import com.example.samdapp.data.slm.LiteRtLmRuntime
import com.example.samdapp.data.slm.LocalAccelerator
import com.example.samdapp.data.slm.LOCAL_SLM_DEFAULT_ACCELERATORS
import com.example.samdapp.data.slm.LocalSlmEngine
import com.example.samdapp.domain.slm.SlmEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Singleton

/**
 * Dev-only holder for [LocalSlmEngine] (on-device SLM memo 4.1, 4.2), and the dev flavor's one
 * readback override (ruling R-DEV, memo 16): the seam runs on [LocalSlmEngine]. `SlmNetworkModule`
 * uses it when present; staging and prod have no such module, so they keep the remote engine. The
 * owner of the local data is the `device_data_owner` marker in every flavor (facility-scope FS-7),
 * which superseded R-DEV's session-facility owner. `SlmEngineIsUnreachableFromPresentationTest`
 * allows exactly the engine override below and no other binding outside `SlmNetworkModule`.
 */
@Module
@InstallIn(SingletonComponent::class)
object LocalSlmModule {

    /**
     * Built on first injection; the memory-pressure callbacks are registered then (S7, memo 10),
     * from the dev source set only. Nothing is loaded before the first generation, so there is
     * nothing to release before the engine exists.
     */
    @Provides
    @Singleton
    fun provideLocalSlmEngine(@ApplicationContext context: Context): LocalSlmEngine =
        localSlmEngine(context).also { context.applicationContext.registerComponentCallbacks(LocalSlmComponentCallbacks(it)) }

    @Provides @DevReadbackOverride
    fun provideDevReadbackEngine(engine: LocalSlmEngine): SlmEngine = engine
}

/**
 * The one construction path, shared by the holder and the dev instrumented tests. The artifact
 * lives at `filesDir/slm/<sha256>.litertlm` (memo 9.1).
 */
// NewApi: the engine checks sdkInt and refuses below API 29 before it ever calls thermalStatus (C-4).
@SuppressLint("NewApi")
fun localSlmEngine(
    context: Context,
    accelerators: List<LocalAccelerator> = LOCAL_SLM_DEFAULT_ACCELERATORS,
    /** Only the parity harness and the GPU smoke pass true: the GPU backend is not selected (V-1). */
    investigateGpu: Boolean = false,
): LocalSlmEngine {
    val powerManager = context.getSystemService(PowerManager::class.java)
    return LocalSlmEngine(
        runtime = LiteRtLmRuntime(),
        artifactDir = File(context.filesDir, "slm"),
        cacheDir = context.cacheDir,
        sdkInt = Build.VERSION.SDK_INT,
        thermalStatus = { powerManager.currentThermalStatus },
        dispatcher = Executors.newSingleThreadExecutor { Thread(it, "slm-local") }.asCoroutineDispatcher(),
        accelerators = accelerators,
        allowUnselectedGpu = investigateGpu,
    )
}

/**
 * Releases the local engine under memory pressure (S7, memo 10, Y-16): `onTrimMemory` at
 * `TRIM_MEMORY_BACKGROUND` and above, and `onLowMemory`. The engine defers the release behind a
 * running generation.
 */
class LocalSlmComponentCallbacks(private val engine: LocalSlmEngine) : ComponentCallbacks2 {

    override fun onTrimMemory(level: Int) = engine.onTrimMemory(level)

    override fun onLowMemory() = engine.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)

    override fun onConfigurationChanged(newConfig: Configuration) = Unit
}
