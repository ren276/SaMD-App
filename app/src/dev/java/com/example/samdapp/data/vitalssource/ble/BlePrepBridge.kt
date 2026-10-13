package com.example.samdapp.data.vitalssource.ble

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Carries one preparation step (the Nearby devices permission, or the companion-device association)
 * from the BLE source to [BlePrepActivity] and its answer back (addendum B1, 3.6). One instance per
 * process.
 *
 * The source suspends in [prepare]; [launch] starts the activity with the request id; the activity
 * reads the request with [pending] and answers with [complete].
 *
 * A request leaves [pending] the moment [prepare] returns, for any reason: answered, the 120 s
 * ceiling, or cancellation because the worker pressed Stop or left the screen. From then on it is
 * abandoned: [pending] is null, so the activity finishes without acting, and [complete] returns false
 * and resumes nothing. After process death the map is empty, so a recreated activity finds no request
 * and finishes too.
 *
 * The ceiling is the preparation's own (Q2). It is outside the 25 s acquisition cap, because a human
 * is answering a system dialog here, not a hub answering a write.
 */
class BlePrepBridge(
    private val launch: (Request) -> Unit,
    private val ceilingMs: Long = CEILING_MS,
) {
    enum class Kind { ASSOCIATION, CONNECT_PERMISSION }

    enum class Answer {
        /** The association was created, or the permission granted. */
        GRANTED,

        /** The worker dismissed or rejected the dialog, or denied the permission. */
        REFUSED,

        /** The system could not run the step: Bluetooth or Location off, or a companion-device error. */
        UNAVAILABLE,

        /** Nobody answered within the ceiling. */
        TIMED_OUT,
    }

    data class Request(val id: Long, val kind: Kind, val hubId: String)

    private val ids = AtomicLong()
    private val open = ConcurrentHashMap<Long, Pair<Request, CompletableDeferred<Answer>>>()

    suspend fun prepare(kind: Kind, hubId: String): Answer {
        val request = Request(ids.incrementAndGet(), kind, hubId)
        val answer = CompletableDeferred<Answer>()
        open[request.id] = request to answer
        try {
            launch(request)
            return withTimeoutOrNull(ceilingMs) { answer.await() } ?: Answer.TIMED_OUT
        } finally {
            open.remove(request.id)
        }
    }

    /** The request still waiting for [id], or null when it was abandoned or never existed here. */
    fun pending(id: Long): Request? = open[id]?.first

    /** Answers [id]. False means the request was abandoned and nothing was resumed. */
    fun complete(id: Long, answer: Answer): Boolean = open.remove(id)?.second?.complete(answer) ?: false

    companion object {
        const val CEILING_MS = 120_000L
    }
}
