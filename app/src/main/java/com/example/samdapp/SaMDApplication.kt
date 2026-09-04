package com.example.samdapp

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.samdapp.data.local.document.sweepOrphanedCaptureSessions
import com.example.samdapp.presentation.documents.sweepOrphanedViewerTempFiles
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** [Configuration.Provider] hands WorkManager the Hilt-aware [HiltWorkerFactory] (Phase 6b), so
 *  `@HiltWorker` [com.example.samdapp.data.sync.SyncPushWorker] can receive its injected
 *  dependencies. Pairs with AndroidManifest.xml removing WorkManager's default androidx-startup
 *  self-initialization, which would otherwise construct WorkManager with the stock factory first. */
@HiltAndroidApp
class SaMDApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // H-18, Build 3a: a process death mid-view leaves a decrypted document-viewer temp file
        // behind (the `finally`/onCleared cleanup only covers a clean exit) — swept here so
        // plaintext PHI never outlives the app process that decrypted it.
        sweepOrphanedViewerTempFiles(this)
        // H-18, Build 3b: the same argument for the camera-capture side. A capture session's page
        // list lives only in ViewModel state, so no session can survive process death - any
        // session directory present at process start is orphaned by definition. Kept as a second,
        // separate call rather than folded into the sweep above: the two cover disjoint
        // directories, and merging them would hide which one failed.
        sweepOrphanedCaptureSessions(this)
    }
}
