package com.example.samdapp

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
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
}
