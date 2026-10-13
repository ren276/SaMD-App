package com.example.samdapp.data.vitalssource

import com.example.samdapp.domain.vitalssource.AcquisitionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** A typed result, or a failure at this assertion rather than a ClassCastException. */
inline fun <reified T : AcquisitionResult> AcquisitionResult.expect(): T {
    assertTrue("expected ${T::class.simpleName}, got $this", this is T)
    return this as T
}

/** The one element, or a failure at this assertion rather than a NoSuchElementException. */
fun <T> List<T>.only(what: String): T {
    assertEquals("exactly one $what: $this", 1, size)
    return single()
}
