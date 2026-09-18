package com.example.samdapp.presentation.navigation

import android.os.Bundle
import androidx.savedstate.SavedState

/**
 * Test-only helpers that treat a persisted [SavedState] as opaque and walk it generically.
 *
 * Deliberately not written against the encoder's internal layout: these tests are about what ends
 * up in the Bundle, and hard-coding a key path would make them pass for the wrong reason the day
 * androidx changes how it nests things.
 */

/** Every string that appears anywhere in the payload, keys included, joined for substring checks. */
@Suppress("DEPRECATION")
fun SavedState.flattenToString(): String = buildString {
    fun walk(value: Any?) {
        when (value) {
            is Bundle -> value.keySet().forEach { key ->
                append(key).append(' ')
                walk(value.get(key))
            }
            is CharSequence -> append(value).append(' ')
            is Iterable<*> -> value.forEach(::walk)
            is Array<*> -> value.forEach(::walk)
            else -> value?.let { append(it.toString()).append(' ') }
        }
    }
    walk(this@flattenToString)
}

/**
 * Rewrites every stored occurrence of a class name, simulating a route class that was renamed,
 * moved or deleted by an app update. Returns how many values were replaced, so a test can fail
 * loudly if the wire format stopped carrying class names rather than silently passing.
 */
@Suppress("DEPRECATION")
fun SavedState.renameStoredClass(from: String, to: String): Int {
    var replaced = 0
    fun walk(bundle: Bundle) {
        for (key in bundle.keySet().toList()) {
            when (val value = bundle.get(key)) {
                is String -> if (value == from) {
                    bundle.putString(key, to)
                    replaced++
                }
                is Bundle -> walk(value)
                is ArrayList<*> -> value.forEach { if (it is Bundle) walk(it) }
                is Array<*> -> value.forEach { if (it is Bundle) walk(it) }
                else -> Unit
            }
        }
    }
    walk(this)
    return replaced
}
