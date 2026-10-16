package com.example.samdapp.di

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.example.samdapp.data.slm.LiteRtLmRuntime
import com.example.samdapp.data.slm.LocalAccelerator
import com.example.samdapp.data.slm.LocalSlmEngine
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
 * Dev-only holder for [LocalSlmEngine] (on-device SLM memo 4.1, 4.2). Provides the concrete class
 * only: the generation interface stays bound to the remote engine in every flavor, and
 * `SlmEngineIsUnreachableFromPresentationTest` fails if any module but `SlmNetworkModule` binds it.
 */
@Module
@InstallIn(SingletonComponent::class)
object LocalSlmModule {

    @Provides
    @Singleton
    fun provideLocalSlmEngine(@ApplicationContext context: Context): LocalSlmEngine = localSlmEngine(context)
}

/**
 * The one construction path, shared by the holder and the dev instrumented tests. The artifact
 * lives at `filesDir/slm/<sha256>.litertlm` (memo 9.1).
 */
fun localSlmEngine(
    context: Context,
    accelerators: List<LocalAccelerator> = listOf(LocalAccelerator.GPU, LocalAccelerator.CPU),
): LocalSlmEngine {
    val powerManager = context.getSystemService(PowerManager::class.java)
    return LocalSlmEngine(
        runtime = LiteRtLmRuntime(),
        artifactDir = File(context.filesDir, "slm"),
        cacheDir = context.cacheDir,
        // ponytail: below API 29 there is no thermal status to read, so nothing refuses; fleet ruling Y-12.
        thermalStatus = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) powerManager.currentThermalStatus
            else PowerManager.THERMAL_STATUS_NONE
        },
        dispatcher = Executors.newSingleThreadExecutor { Thread(it, "slm-local") }.asCoroutineDispatcher(),
        accelerators = accelerators,
    )
}
