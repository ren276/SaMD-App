package com.example.samdapp.data.repository

import com.example.samdapp.domain.DataError
import kotlinx.coroutines.CancellationException

/** The repository error boundary: catches platform/library exceptions and remaps them to
 * [DataError] — but rethrows [CancellationException] first, since a broad catch here would
 * otherwise swallow it and break structured concurrency. */
suspend fun <T> asDataResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(DataError.Local(e))
}
