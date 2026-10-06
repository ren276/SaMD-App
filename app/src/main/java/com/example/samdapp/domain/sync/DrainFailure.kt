package com.example.samdapp.domain.sync

/**
 * Why the last outbox drain stopped, as Home needs to say it. Null in [SyncState.lastDrainFailure]
 * means the last drain succeeded, or none has failed since the app started (the value is kept in
 * memory only, and every Home caption stays true when it is unknown).
 */
enum class DrainFailure {
    /** The batch never reached the server: no route, a dropped connection, or a body that was
     *  not a problem document. */
    NO_CONNECTION,

    /** The server refused the batch with a `SAMD-AUTH-*` code: the session ended and could not
     *  be refreshed. Signing in again fixes it. */
    SIGN_IN,

    /** The server refused the whole batch for any other reason (for example a database outage, or
     *  a server defect that 500s the batch). Nothing on this phone fixes it. */
    SERVER_REFUSED,

    /** This phone could not prepare the batch: the persisted in-flight batch could not be read. */
    LOCAL_STORE,
}
