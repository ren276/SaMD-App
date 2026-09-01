package com.example.samdapp.domain

sealed class DataError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Local(cause: Throwable) : DataError("Local storage error", cause)
    class NotFound(what: String) : DataError("$what not found")

    /** A write the repository declined on policy grounds, as opposed to one that failed. Distinct
     *  from [Local] on purpose: [com.example.samdapp.data.repository.asDataResult] wraps every
     *  thrown exception as [Local] ("Local storage error"), so a deliberate refusal that threw
     *  would reach the caller disguised as a storage fault. Returning this instead keeps the
     *  refusal legible in the error channel. First use is the `VOICE_UNCONFIRMED` write-refusal
     *  in [com.example.samdapp.data.repository.ConsultationRepositoryImpl.saveConsultation]
     *  (`scratchpad/pr3-voice-gate-design-memo.md` B.2).
     *
     *  [message] is worker-facing text with no internal model/column names, since call sites such
     *  as `ConsultationViewModel` show `error.message` directly in UI state. [reason] carries the
     *  full technical detail (which field, which enum value) for logs or a future distinguishing
     *  branch, kept off the screen. */
    class Refused(val reason: String, message: String) : DataError(message)
}
