package com.example.samdapp.data.remote

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private data class DobHolder(val dateOfBirth: LocalDate?)

/**
 * ABDM's `profile/account` response has no combined `dob` field, only separate
 * `yearOfBirth`/`monthOfBirth`/`dayOfBirth` strings. Decision D3 (`docs/requirements/
 * abha-internal-contract.md`) is that when only the year is present, the backend's canonical
 * `date_of_birth` is the bare year string (`"1991"`), never a fabricated `"1991-01-01"`.
 *
 * `LocalDate.parse("1991")` throws `DateTimeParseException`, which previously escaped
 * [LocalDateGsonAdapter] uncaught and crashed the enrolment coroutine on exactly the accounts D3
 * exists to handle. This proves the fallback: null, not a fabricated date, not a thrown exception.
 */
class SyncGsonAdaptersTest {

    private val gson = SyncGson.create()

    @Test
    fun `a bare year value deserializes to null, not a fabricated date`() {
        val result = gson.fromJson("""{"dateOfBirth":"1991"}""", DobHolder::class.java)

        assertNull(result.dateOfBirth)
    }

    @Test
    fun `a full ISO date still deserializes unchanged`() {
        val result = gson.fromJson("""{"dateOfBirth":"1991-04-12"}""", DobHolder::class.java)

        assertEquals(LocalDate.of(1991, 4, 12), result.dateOfBirth)
    }

    @Test
    fun `an explicit JSON null still deserializes to null`() {
        val result = gson.fromJson("""{"dateOfBirth":null}""", DobHolder::class.java)

        assertNull(result.dateOfBirth)
    }

    @Test
    fun `serializing a LocalDate round-trips through the same adapter`() {
        val json = gson.toJson(DobHolder(LocalDate.of(1991, 4, 12)))
        val result = gson.fromJson(json, DobHolder::class.java)

        assertEquals(LocalDate.of(1991, 4, 12), result.dateOfBirth)
    }
}
