package com.example.samdapp.domain

sealed class DataError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Local(cause: Throwable) : DataError("Local storage error", cause)
    class NotFound(what: String) : DataError("$what not found")
}
