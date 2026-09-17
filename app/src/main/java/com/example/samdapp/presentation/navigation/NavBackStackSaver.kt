package com.example.samdapp.presentation.navigation

import android.os.Parcel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.savedstate.SavedState
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicReference

/**
 * Why this file exists instead of `androidx.navigation3.runtime.rememberNavBackStack`.
 *
 * Verified against navigation3-runtime 1.1.4's own sources (the version this project resolves):
 * `rememberNavBackStack` is `rememberNavBackStack(vararg elements: NavKey)`. It takes no
 * `inputs` and it has no failure path. This app needs both:
 *
 *  1. The stack must be DISCARDED when the signed-in worker changes. `MainNavHost` used to rely
 *     on a plain `remember` to give every new sign-in a clean stack; a saveable stack breaks that
 *     guarantee, and without a session key a sign-out and sign-in inside one process can hand
 *     worker B the tail of worker A's stack, with worker A's patientId in it.
 *  2. A restore must NEVER throw. `NavKeySerializer` deserializes via
 *     `Class.forName(className).kotlin.serializer()`, so a route class renamed, moved or deleted
 *     between the build that saved and the build that restored throws from inside composition.
 *     Uncaught, that is a crash on every launch until app data is cleared: a permanent boot loop
 *     on a clinical device, which is worse than losing the stack.
 *
 * Do not "simplify" this back to `rememberNavBackStack`. It cannot express either guarantee.
 */

/** Concrete element serializer, so the generic parameter never has to be inferred at a use site. */
private object NavKeyElementSerializer : NavKeySerializer<NavKey>()

/**
 * The persisted shape. [ownerUserId] is stored INSIDE the payload rather than relying only on
 * `rememberSaveable`'s `inputs`, for two reasons: a mismatch is then observable, so the discard
 * can be audited with a reason rather than happening silently, and the check holds even if the
 * stack is ever restored somewhere the input key is not in scope.
 */
@Serializable
private data class NavStackEnvelope(
    val ownerUserId: String,
    val entries: List<@Serializable(NavKeyElementSerializer::class) NavKey>,
)

/** Why a saved stack was thrown away. Carried to the audit row; never carries route contents. */
enum class NavStackDiscardReason(val value: String) {
    CORRUPT("corrupt"),
    TOO_LARGE("too_large"),
    SESSION_CHANGED("session_changed"),
}

/**
 * A [Saver] cannot log: it has no coroutine scope and no injected [AuditLogger]. It parks the
 * reason here instead, and `MainNavHost` drains it once on composition. Static because the save
 * and restore lambdas outlive any one composition.
 */
object NavStackRestoreReporter {
    private val pending = AtomicReference<NavStackDiscardReason?>(null)

    internal fun report(reason: NavStackDiscardReason) {
        pending.set(reason)
    }

    /** Returns the pending reason, if any, and clears it, so one discard logs exactly one row. */
    fun consume(): NavStackDiscardReason? = pending.getAndSet(null)
}

/**
 * Budget for the saved stack. The Activity's saved-state Bundle crosses a Binder transaction,
 * whose hard ceiling is roughly 1 MB SHARED with everything else in that transaction, so the nav
 * stack's own budget sits far below it.
 *
 * Realistic depth is under 10: `switchTab` clears the stack on every tab switch, and the terminal
 * screens reset to a single `Home` entry. With ids-only arguments an entry costs on the order of
 * 150 to 250 bytes, most of it the fully qualified class name. 64 KB is therefore roughly 300
 * entries of headroom over a stack that should never exceed 10: exceeding it means something is
 * looping, and dropping to `Home` is the right answer for a loop.
 */
private const val MAX_SAVED_STACK_BYTES = 64 * 1024

/** Hard entry cap, checked before encoding so a runaway stack is never even serialized. */
private const val MAX_SAVED_STACK_ENTRIES = 50

private fun SavedState.byteSize(): Int {
    val parcel = Parcel.obtain()
    return try {
        parcel.writeBundle(this)
        parcel.dataSize()
    } finally {
        parcel.recycle()
    }
}

/**
 * The saver used by `MainNavHost`. [ownerUserId] is the signed-in worker's id.
 *
 * `save` returns null when the stack is over budget, which persists nothing at all rather than
 * risking a `TransactionTooLargeException` that would take the whole Activity down with it.
 * `restore` returns null on a foreign session or on any decode failure, and a null restore makes
 * `rememberSaveable` fall back to its `init`, which is `NavBackStack(Home)`.
 */
@Composable
fun rememberNavBackStackSaver(ownerUserId: String): Saver<NavBackStack<NavKey>, SavedState> =
    remember(ownerUserId) { navBackStackSaver(ownerUserId) }

internal fun navBackStackSaver(ownerUserId: String): Saver<NavBackStack<NavKey>, SavedState> =
    Saver(
        save = { stack ->
            if (stack.size > MAX_SAVED_STACK_ENTRIES) {
                NavStackRestoreReporter.report(NavStackDiscardReason.TOO_LARGE)
                return@Saver null
            }
            val encoded = runCatching {
                encodeToSavedState(
                    NavStackEnvelope.serializer(),
                    NavStackEnvelope(ownerUserId = ownerUserId, entries = stack.toList()),
                )
            }.getOrNull()
            when {
                encoded == null -> {
                    // A route that cannot be written is the same defect as one that cannot be
                    // read, and it must not take the save of everything else down with it.
                    NavStackRestoreReporter.report(NavStackDiscardReason.CORRUPT)
                    null
                }
                encoded.byteSize() > MAX_SAVED_STACK_BYTES -> {
                    NavStackRestoreReporter.report(NavStackDiscardReason.TOO_LARGE)
                    null
                }
                else -> encoded
            }
        },
        restore = { saved ->
            val envelope = runCatching {
                decodeFromSavedState(NavStackEnvelope.serializer(), saved)
            }.getOrNull()
            when {
                envelope == null -> {
                    NavStackRestoreReporter.report(NavStackDiscardReason.CORRUPT)
                    null
                }
                envelope.ownerUserId != ownerUserId -> {
                    NavStackRestoreReporter.report(NavStackDiscardReason.SESSION_CHANGED)
                    null
                }
                else -> NavBackStack(*envelope.entries.toTypedArray())
            }
        },
    )
