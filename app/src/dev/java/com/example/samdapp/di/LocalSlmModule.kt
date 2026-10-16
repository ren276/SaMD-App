package com.example.samdapp.di

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.example.samdapp.data.slm.LiteRtLmRuntime
import com.example.samdapp.data.slm.LocalAccelerator
import com.example.samdapp.data.slm.LocalSlmEngine
import com.example.samdapp.domain.auth.SessionFacilitySource
import com.example.samdapp.domain.slm.LocalDataOwnerFacility
import com.example.samdapp.domain.slm.OwningFacility
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
 * Dev-only holder for [LocalSlmEngine] (on-device SLM memo 4.1, 4.2), and the dev flavor's two
 * readback overrides (ruling R-DEV, memo 16): the seam runs on [LocalSlmEngine], and the local data
 * is owned by the signed-in session's facility. `SlmNetworkModule` uses them when present; staging
 * and prod have no such module, so they keep the remote engine and an owner that is always UNKNOWN.
 * `SlmEngineIsUnreachableFromPresentationTest` allows exactly the engine override below and no
 * other binding outside `SlmNetworkModule`.
 */
@Module
@InstallIn(SingletonComponent::class)
object LocalSlmModule {

    @Provides
    @Singleton
    fun provideLocalSlmEngine(@ApplicationContext context: Context): LocalSlmEngine = localSlmEngine(context)

    @Provides @DevReadbackOverride
    fun provideDevReadbackEngine(engine: LocalSlmEngine): SlmEngine = engine

    /**
     * Dev holds mock data only, so the owner of the local data is taken to be the signed-in
     * session's facility: the facility check passes for any signed-in worker whose login carried a
     * facility id, and refuses when signed out. This is not a facility scope and must never ship:
     * staging and prod wait for the product-wide facility-scope PR (memo 15).
     */
    @Provides @DevReadbackOverride
    fun provideDevLocalDataOwnerFacility(session: SessionFacilitySource): LocalDataOwnerFacility =
        object : LocalDataOwnerFacility {
            override suspend fun owningFacility(): OwningFacility =
                session.currentFacility()?.let { OwningFacility.Known(it.facilityId) } ?: OwningFacility.Unknown
        }
}

/**
 * The one construction path, shared by the holder and the dev instrumented tests. The artifact
 * lives at `filesDir/slm/<sha256>.litertlm` (memo 9.1).
 */
// NewApi: the engine checks sdkInt and refuses below API 29 before it ever calls thermalStatus (C-4).
@SuppressLint("NewApi")
fun localSlmEngine(
    context: Context,
    accelerators: List<LocalAccelerator> = listOf(LocalAccelerator.GPU, LocalAccelerator.CPU),
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
    )
}
