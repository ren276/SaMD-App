package com.example.samdapp.domain.kernel

/**
 * What a PHC worker can do about a [KernelFailure], and the only thing the assessment screen
 * branches on: press again, or a person must act first. There used to be a third value promising
 * the assessment "runs on its own once connectivity returns". Nothing re-runs it (the worker always
 * finishes after writing the row, and only the send and Try again enqueue it), so that promise was
 * false and hid the only button that could help. Offering a button on [NEEDS_ACTION] still teaches
 * a worker to press something that can never work, so that case keeps none.
 */
enum class KernelRetryAdvice {
    /** Press again now. The request reached the server and one hop was slow; the next press may
     *  well land. The screen shows a live retry button. */
    RETRY_NOW,

    /** A person must do something first: fix a record, sign in again, or escalate. Pressing again
     *  unchanged fails identically and forever. The screen offers no retry button at all. */
    NEEDS_ACTION,
}

/**
 * Why no assessment exists for a case. The device-side failure vocabulary for one hop:
 * this app to `backend/core`'s kernel proxy (`POST /api/v1/assess`).
 *
 * **Shaped after `KernelCallOutcome` and `SlmCallOutcome` in `backend/core/app/models/enums.py`,
 * and deliberately not a reuse of either.** Those two describe a different hop, backend/core to a
 * model service on the LAN, and they answer an operator's question ("is the kernel unreachable or
 * is its process erroring") from a vantage point that can see both ends. This enum answers a
 * health worker's question ("what do I do about this patient, now") from a phone that can only
 * see its own end. The two vocabularies do not have a value-for-value correspondence and forcing
 * one would produce values no caller can populate: a device cannot tell `KERNEL_ERROR` from
 * `CIRCUIT_OPEN` without being told, and the backend cannot observe [OFFLINE] at all. What is
 * shared is the discipline, one named vocabulary per hop, written down once, per PR-2.
 *
 * **Why ten values and not one.** Until this enum existed, every one of these arrived at
 * `GenerateKernelReportUseCase`'s single `catch (e: Exception)` and left it as
 * [com.example.samdapp.domain.model.InferenceSource.UNAVAILABLE], which produced one string for
 * all of them: "Assessment unavailable ... Tap Retry to run the assessment again." That advice is
 * correct for three of the ten and actively wrong for the rest, and it is worst for
 * [CASE_NOT_ON_SERVER], where the assessment is fine and the patient was never saved.
 *
 * **Why the 502/503/504 family is one value and not five.** `SAMD-KERN-5001/5002/5004/5006/5007`
 * are five genuinely different server-side conditions, and the backend already keeps them apart
 * in `kernel_call_log.outcome`, which is where an operator debugging the kernel looks. On this
 * phone they produce one identical worker action: wait, it will run on its own. Splitting them
 * here would add four values no screen could render differently and no worker could act on.
 * The distinction is kept where it is useful and dropped where it is not, which is the opposite
 * of the collapse this enum exists to undo.
 */
enum class KernelFailure(val advice: KernelRetryAdvice) {
    /** No route to the backend at all: `UnknownHostException`, `ConnectException`. The request
     *  never left the phone, so nothing server-side happened and nothing needs undoing. */
    OFFLINE(KernelRetryAdvice.RETRY_NOW),

    /** `SocketTimeoutException`. The connection was made and the answer did not arrive in time.
     *  The one class where an immediate second press is genuinely reasonable. */
    TIMEOUT(KernelRetryAdvice.RETRY_NOW),

    /** `SSLException`. Not treated as "offline with extra steps": a TLS failure can be a captive
     *  portal, a clock skew, or an interception, and the last of those is a reason to stop
     *  sending patient data rather than to keep pressing a button. Escalation, not retry. */
    SECURE_CONNECTION_FAILED(KernelRetryAdvice.NEEDS_ACTION),

    /** HTTP 401 or 403. The session expired or this worker's role may not assess. Signing in
     *  again is a person's action, and it fixes the 401 case. */
    NOT_AUTHORIZED(KernelRetryAdvice.NEEDS_ACTION),

    /** HTTP 404 `SAMD-ENC-4002`: the backend has no case record with this id, AFTER the assess
     *  gate saw a server version for it (a facility mismatch, or a server that lost the row). The
     *  pre-call causes, a case not sent yet, a duplicate patient, a chain blocked in sync, are
     *  decided by the gate and have their own values below, so this one no longer stands in for
     *  them. Retrying fails identically. */
    CASE_NOT_ON_SERVER(KernelRetryAdvice.NEEDS_ACTION),

    /** HTTP 422 `SAMD-KERN-5003` (the kernel rejected the payload) or `SAMD-KERN-5005` (the H-10
     *  identity guard found an identity field on the kernel boundary). Not retryable unchanged,
     *  and a real defect signal in both directions: 5005 in particular means something that
     *  should never cross the pseudonymization boundary was about to. */
    PAYLOAD_REJECTED(KernelRetryAdvice.NEEDS_ACTION),

    /** HTTP 502/503/504, `SAMD-KERN-5001/5002/5004/5006/5007`. The backend was reached and the
     *  kernel behind it was not usable: unreachable, timed out, circuit open, erroring, or
     *  answering unparseably. One value by design, see this enum's own KDoc. */
    KERNEL_UNAVAILABLE(KernelRetryAdvice.RETRY_NOW),

    /** The backend answered and this app could not read the body. Contract drift on a live
     *  server. Kept distinct from [OFFLINE] for exactly the reason
     *  [com.example.samdapp.domain.abha.AbhaApiResult.ProtocolViolation] is: a reached-but-
     *  unintelligible server must never be treated as "just offline, try later". */
    MALFORMED_RESPONSE(KernelRetryAdvice.NEEDS_ACTION),

    /** A bug on this phone, in this app, while handling a response that arrived fine
     *  (`IllegalStateException`, `NullPointerException`). Distinguished from every server-side
     *  class because the remedy is a bug report, and because silently blaming the network for an
     *  app defect is how the defect survives. */
    DEVICE_ERROR(KernelRetryAdvice.NEEDS_ACTION),

    /** The backend was reached, answered non-2xx, and the code was absent, unparseable, or one
     *  this build has never heard of. A code this app does not recognise is precisely the case
     *  where guessing an action is least safe, so it gets the general failure and an escalation,
     *  never a crash and never silence. Same reasoning as
     *  `AbhaEnrolResult`'s `messageForCode` fallback. */
    UNKNOWN(KernelRetryAdvice.NEEDS_ACTION),

    // The four below are decided on this phone before any call, by the assess gate
    // (AssessGate.kt) and the runner's local resolution. Nothing was sent, so they are not this
    // hop's failures in the strict sense; they share the vocabulary because the screen renders
    // every "no assessment" cause from one value.

    /** No local case record, vitals or consultation to build a payload from. The AI was never
     *  asked. Pressing again unchanged cannot supply the missing data. */
    RECORD_INCOMPLETE(KernelRetryAdvice.NEEDS_ACTION),

    /** The case is not on the server because its patient was refused as a duplicate (another
     *  patient holds this ABHA number). Nothing on this phone can fix it. */
    PATIENT_DUPLICATE(KernelRetryAdvice.NEEDS_ACTION),

    /** The case is not on the server because a row in its chain (patient, encounter or case
     *  record) is FAILED or CONFLICT. The Home review list says which and why. */
    CASE_SYNC_BLOCKED(KernelRetryAdvice.NEEDS_ACTION),

    /** The case is not on the server yet: its chain is still PENDING or RETRYABLE. Pressing again
     *  after it has been sent can succeed. */
    CASE_NOT_SENT_YET(KernelRetryAdvice.RETRY_NOW),
}
