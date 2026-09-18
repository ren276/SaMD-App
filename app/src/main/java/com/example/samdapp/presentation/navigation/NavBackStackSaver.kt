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
 * What one entry becomes in the SERIALIZED stack. [Keep] is the overwhelming majority.
 *
 * [Drop] removes the entry outright, which is only ever chosen when the entry directly beneath is
 * where the worker came from and is itself safe, so dropping lands them one step back.
 * [Replace] swaps in a target the unsafe route can build entirely from its OWN arguments.
 */
private sealed interface EntryDecision {
    data object Keep : EntryDecision

    data object Drop : EntryDecision

    data class Replace(val target: NavKey) : EntryDecision
}

/**
 * Two [Compounder] entries describe the same resumed case.
 *
 * `followUpOfEncounterId` is deliberately EXCLUDED from this comparison, and that exclusion is
 * load-bearing rather than sloppy: on the resume path it is never read. `CompounderViewModel`
 * branches on `resumeEncounterId != null && resumeCaseRecordId != null` and, in that branch, only
 * logs `ENCOUNTER_RESUMED` and returns the two ids; `followUpOfEncounterId` is read solely in the
 * else branch, as `startCaseUseCase(patientId, followUpOfEncounterId)`. A restored entry always
 * carries both resume ids and therefore always takes the resume branch.
 *
 * Do not "complete" this to full data-class equality. A follow-up visit's live entry carries a
 * real `followUpOfEncounterId` while a target synthesized from [ConsultationRoute] carries null,
 * so full equality would fail and leave two adjacent resume-shape `Compounder` entries for one
 * visit: harmless, but it weakens the one-live-path-per-case invariant the tests assert.
 */
private fun resumesSameCase(below: Compounder, target: Compounder): Boolean =
    below.patientId == target.patientId &&
        below.resumeEncounterId == target.resumeEncounterId &&
        below.resumeCaseRecordId == target.resumeCaseRecordId

/**
 * The save-time transform. THE INVARIANT IT ENFORCES: the serialized stack contains no route
 * carrying free text or a direct identifier, and every transformed entry maps to a safe ancestor
 * that composes without a duplicate clinical write.
 *
 * WHY SAVE TIME AND NOT RESTORE TIME. `onSaveInstanceState` writes the Bundle before the process
 * dies. A policy applied when the stack is restored runs after that write has already landed, so
 * it can only decide what the worker comes back to; it removes nothing from the Bundle, which was
 * the entire point of reshaping these routes. Transforming here is also strictly cheaper: the
 * live back stack is untouched, so the worker's current screen and the forward path are
 * unaffected, and a restore simply lands on an already-safe stack with no second mechanism.
 *
 * THE RULE, so this stays one mechanism rather than a per-route special-case list: every unsafe
 * route names a target. If the entry immediately beneath already equals that target the entry is
 * dropped; otherwise the target replaces it in place. Routes whose safe ancestor is simply "one
 * step back" are unconditional drops.
 *
 * ORDER INDEPENDENCE. Every decision is computed against the ORIGINAL stack, and only then are
 * they applied. `Register(abhaId)` can sit directly above an `AbhaOtpRoute` that is itself being
 * transformed, and a pass that read a partially rewritten stack would give a different answer
 * depending on which index it visited first.
 *
 * [stack] is a plain `List` copy. The live `NavBackStack` is never passed in and never mutated.
 */
internal fun transformForSave(stack: List<NavKey>): List<NavKey> {
    val decisions = MutableList<EntryDecision>(stack.size) { EntryDecision.Keep }

    stack.forEachIndexed { index, entry ->
        val below = stack.getOrNull(index - 1)
        when (entry) {
            // Clinical free text (`chiefComplaint`). The target is built from this route's own
            // patientId/encounterId/caseRecordId, NOT by trusting the Compounder beneath to
            // already be in resume shape. That independence is the point: if the in-place entry
            // rewrite in AppNavHost ever regresses, a transform that relied on it would land the
            // worker on a START-shape Compounder, which re-runs StartCaseUseCase and mints a
            // second encounter and case record for one visit. `followUpOfEncounterId` is null
            // here because it is unused on the resume path; see resumesSameCase.
            is ConsultationRoute -> {
                val target = Compounder(
                    patientId = entry.patientId,
                    resumeEncounterId = entry.encounterId,
                    resumeCaseRecordId = entry.caseRecordId,
                )
                // Unlike the other routes, this one's ancestor is a STATEFUL screen rather than a
                // menu, so "is it already down there" is asked of the whole stack below, not only
                // of the entry directly beneath. Looking one step down was not enough: any entry
                // sitting between the two (the all-routes fixture in NavBackStackPhiFreeTest is
                // one) made the check miss and emit a SECOND Compounder for the same case, which
                // is precisely the duplicate this transform exists to avoid. The nearest one wins,
                // so the answer is deterministic.
                // The INDEX is tracked, not just the value. `indexOf` would resolve the entry by
                // data-class equality and return the FIRST equal one, so a stack holding two equal
                // start-shape Compounder entries would have the repair applied to the lower one
                // while the nearest stayed armed below the restored screen: exactly the state this
                // branch exists to prevent.
                val ancestorIndex = stack.take(index)
                    .indexOfLast { it is Compounder && it.patientId == entry.patientId }
                val ancestor = if (ancestorIndex >= 0) stack[ancestorIndex] as Compounder else null
                when {
                    ancestor == null -> decisions[index] = EntryDecision.Replace(target)
                    resumesSameCase(ancestor, target) -> decisions[index] = EntryDecision.Drop
                    // A same-patient Compounder below that is NOT already this case is a start
                    // shape left behind by a missing rewrite. Repair it in place and drop this
                    // entry, rather than stacking a resume shape above a start shape and leaving
                    // the start shape armed below the restored screen, where backing out once
                    // would re-run StartCaseUseCase.
                    else -> {
                        decisions[index] = EntryDecision.Drop
                        decisions[ancestorIndex] = EntryDecision.Replace(
                            Compounder(
                                patientId = entry.patientId,
                                followUpOfEncounterId = ancestor.followUpOfEncounterId,
                                resumeEncounterId = entry.encounterId,
                                resumeCaseRecordId = entry.caseRecordId,
                            ),
                        )
                    }
                }
            }

            // TranscriptionViewModel transcribes and PERSISTS in init, with no confirmation gate,
            // and logs TRANSCRIPTION_COMPLETED. Restoring this entry would re-run all of that
            // against an audioUri whose file may be gone. The target is not the entry beneath
            // (KernelAssessmentRoute pushes this one), so it is synthesized -- which this route
            // can do, because it carries caseRecordId. Same branch KernelAssessmentScreen already
            // takes for "no audio, or flag off".
            is TranscriptionRoute -> {
                decisions[index] = replaceOrDrop(below, AcknowledgementRoute(entry.caseRecordId))
            }

            // A national health identifier. Register is pushed from four sites with three
            // different entries beneath, so "one step back" is not well defined and this needs a
            // single deterministic target. Register(null) carries no identifier at all and is
            // left alone.
            is Register -> {
                if (entry.abhaId != null) decisions[index] = replaceOrDrop(below, AbhaEntry)
            }

            // Unconditional drops: for each of these the entry beneath is both where the worker
            // came from and safe, so there is nothing to synthesize.
            //   AbhaOtpRoute       -> AbhaLogin, a bare data object; the typed ABHA id lives in
            //                         AbhaLoginViewModel's state, never in a route argument.
            //   AbhaCreateOtpRoute -> AbhaAadhaarEntry. Drops `maskedMobile` and a sessionId whose
            //                         backend registration transaction is time-bounded and may
            //                         well have expired by the time anyone restores this.
            //   AbhaProfileRoute   -> PatientSummary. A read-only view with no entry side effects,
            //                         so dropping it is safe whatever sits beneath.
            is AbhaOtpRoute, is AbhaCreateOtpRoute, is AbhaProfileRoute -> {
                decisions[index] = EntryDecision.Drop
            }

            // EmergencyOverrideRoute is deliberately NOT transformed. After the phase 1 reshape it
            // carries (patientId, encounterId) and nothing else; both are opaque generated ids
            // (patientId is a secureRandom draw over a fixed alphabet in RegisterPatientUseCase,
            // encounterId comes from startEncounter), so there is nothing to strip. It is a red
            // flag the worker must still see on return, and re-deriving its reasons from the
            // persisted vitals row is exactly what makes restoring it safe.
            else -> Unit
        }
    }

    return stack.mapIndexedNotNull { index, entry ->
        when (val decision = decisions[index]) {
            EntryDecision.Keep -> entry
            EntryDecision.Drop -> null
            is EntryDecision.Replace -> decision.target
        }
    }
}

/** The rule in one place: drop when the entry beneath is already the target, else replace. */
private fun replaceOrDrop(below: NavKey?, target: NavKey): EntryDecision =
    if (below == target) EntryDecision.Drop else EntryDecision.Replace(target)

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
            // THE COPY IS TAKEN HERE. `toList()` snapshots the live NavBackStack into a plain
            // List, and everything below this line works on that List. The live stack is read and
            // never written: no set, add, remove or clear anywhere in this lambda. That is not a
            // detail. `onSaveInstanceState` also fires when the app is merely backgrounded, so a
            // transform that mutated the live stack would turn a worker's ConsultationRoute into
            // a Compounder the moment they took a phone call, losing their place with no crash to
            // explain it. NavBackStackSaverTest pins this from the other side.
            val transformed = transformForSave(stack.toList())
            // Budget checks run AFTER the transform, deliberately. The transform only ever shrinks
            // or preserves the stack, so a stack marginally over budget can come back under it
            // once the unsafe entries are gone. Checking first would discard a stack the transform
            // would have rescued, costing the worker their place and writing a discard audit row
            // for nothing.
            if (transformed.size > MAX_SAVED_STACK_ENTRIES) {
                NavStackRestoreReporter.report(NavStackDiscardReason.TOO_LARGE)
                return@Saver null
            }
            val encoded = runCatching {
                encodeToSavedState(
                    NavStackEnvelope.serializer(),
                    NavStackEnvelope(ownerUserId = ownerUserId, entries = transformed),
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
