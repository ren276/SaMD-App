package com.example.samdapp.data.remote

import com.example.samdapp.data.remote.dto.SyncPushRequestDto
import com.example.samdapp.data.remote.dto.SyncPushResponseDto

/** Seam between [com.example.samdapp.data.sync.SyncOutboxDrainer] and the network, so the
 *  drainer's pack/send/ack loop — including the crash-resume batch_id-reuse path — is JVM-
 *  testable against a fake, the same way [com.example.samdapp.testutil.FakeCaseRecordRepository]
 *  lets domain repositories be tested without Android framework. */
interface SyncPushService {
    suspend fun push(request: SyncPushRequestDto): SyncPushResult<SyncPushResponseDto>
}
