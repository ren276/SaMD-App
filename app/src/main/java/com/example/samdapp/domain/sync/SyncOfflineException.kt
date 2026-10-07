package com.example.samdapp.domain.sync

/** [SyncStatus.syncNow] refused because the phone is offline: nothing was enqueued and nothing
 *  was sent. Typed so Home can say that, rather than "still trying", which is true only for a
 *  sync that was enqueued and timed out waiting. */
class SyncOfflineException : IllegalStateException("No network available, can't sync while offline")
