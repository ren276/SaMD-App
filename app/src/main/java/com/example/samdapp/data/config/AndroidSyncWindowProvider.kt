package com.example.samdapp.data.config

import android.content.Context
import com.example.samdapp.R
import com.example.samdapp.domain.config.SyncWindowProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidSyncWindowProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : SyncWindowProvider {
    override fun hoursUntilReview(): Int = context.resources.getInteger(R.integer.sync_window_hours)
}
