package com.example.samdapp.data.sync

import com.example.samdapp.data.remote.dto.SyncRecordDto
import dagger.Module
import dagger.BindsOptionalOf
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * A seam for a build that wants to watch the drain, with no implementation outside the dev source
 * set. [SyncOutboxDrainer] receives it as an optional binding, so staging and prod, which bind
 * nothing, run exactly the drain they ran before. It sees ids and sync metadata only.
 */
interface DrainObserver {
    /** Called once at the start of each drain, under the drain lock, before anything is collected. */
    suspend fun onDrainStart()

    /** Called with everything one collect pass returned, before the drain's in-memory
     *  `attempted` filter is applied. */
    fun onCollected(records: List<SyncRecordDto>)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DrainObserverModule {
    @BindsOptionalOf
    abstract fun optionalDrainObserver(): DrainObserver
}
