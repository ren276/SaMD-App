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
 *
 * The companion-device callbacks outlive the activity instance that registered them, so they reach an
 * activity only through here: [dialogReady], [associationCreated] and [associationFailed] act on the
 * [Host] attached for the request now, which after a rotation is the recreated instance, never the
 * destroyed one.
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

    /** The activity instance hosting a request's step. */
    interface Host {
        /** Shows the system dialog, an `IntentSender` ([Any] keeps this class free of Android). */
        fun showDialog(dialog: Any)

        fun finish()
    }

    private val ids = AtomicLong()
    private val open = ConcurrentHashMap<Long, Pair<Request, CompletableDeferred<Answer>>>()
    private val hosts = ConcurrentHashMap<Long, Host>()

    /** A dialog that became ready while no instance was attached, kept for the next one. */
    private val readyDialogs = ConcurrentHashMap<Long, Any>()
    // ponytail: one id per association request in each set, kept for the process's life so a late
    // signal still finds it (a second association signal stays a no-op after the request completed);
    // bounded by how often a human runs the dialog.
    private val dialogsShown: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val associated: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    suspend fun prepare(kind: Kind, hubId: String): Answer {
        val request = Request(ids.incrementAndGet(), kind, hubId)
        val answer = CompletableDeferred<Answer>()
        open[request.id] = request to answer
        try {
            launch(request)
            return withTimeoutOrNull(ceilingMs) { answer.await() } ?: Answer.TIMED_OUT
        } finally {
            open.remove(request.id)
            readyDialogs.remove(request.id)
        }
    }

    /** The request still waiting for [id], or null when it was abandoned or never existed here. */
    fun pending(id: Long): Request? = open[id]?.first

    /** Answers [id]. False means the request was abandoned and nothing was resumed. */
    fun complete(id: Long, answer: Answer): Boolean = open.remove(id)?.second?.complete(answer) ?: false

    /** [host] is the live instance for [id]. A dialog that became ready while none was attached is
     *  shown by it now. */
    fun attach(id: Long, host: Host) {
        hosts[id] = host
        readyDialogs.remove(id)?.let(host::showDialog)
    }

    /** [host] is being destroyed. A newer instance attached for [id] stays attached. */
    fun detach(id: Long, host: Host) {
        hosts.remove(id, host)
    }

    /** `onAssociationPending`: the attached instance shows [dialog], or the next one to attach does. */
    fun dialogReady(id: Long, dialog: Any) {
        dialogsShown += id
        hosts[id]?.showDialog(dialog) ?: run { readyDialogs[id] = dialog }
    }

    /**
     * The system created the association for [id]. Both of its signals come here, the
     * `onAssociationCreated` callback and the dialog's RESULT_OK, and the first wins: it runs [store],
     * answers GRANTED and finishes the attached instance; the second does nothing and returns false.
     * [store] runs for an abandoned request too, because the system holds the association either way.
     */
    fun associationCreated(id: Long, store: () -> Unit): Boolean {
        if (!associated.add(id)) return false
        store()
        complete(id, Answer.GRANTED)
        hosts[id]?.finish()
        return true
    }

    /** `onFailure`: after the dialog was shown, the human's refusal; before it, the system could not
     *  run the step (Bluetooth or Location off, a companion-device error). */
    fun associationFailed(id: Long) {
        complete(id, if (id in dialogsShown) Answer.REFUSED else Answer.UNAVAILABLE)
        hosts[id]?.finish()
    }

    companion object {
        const val CEILING_MS = 120_000L
    }
}
