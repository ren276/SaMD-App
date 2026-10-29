package com.example.samdapp.data.sync

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Memo P2-R9: the hold is observed through the same key isHeld() reads, with the same behavior on a
 *  read error (it propagates), and the in-memory hold emits what hold() and release() set. */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnerMismatchHoldFlowTest {

    private val key = booleanPreferencesKey("owner_mismatch_hold")

    @Test
    fun `the flow reads the owner_mismatch_hold key, once per change`() = runTest {
        val data = flowOf(emptyPreferences(), preferencesOf(key to true), preferencesOf(key to true), emptyPreferences())
        assertEquals(listOf(false, true, false), heldFrom(data).toList())
    }

    @Test
    fun `a read error propagates, as it does from isHeld`() = runTest {
        val failing = flow<Preferences> { throw IOException("disk") }
        val thrown = runCatching { heldFrom(failing).first() }.exceptionOrNull()
        assertTrue("expected the IOException, got $thrown", thrown is IOException)
    }

    @Test
    fun `the in-memory hold emits true on hold and false on release`() = runTest {
        val hold = MemoryOwnerMismatchHold()
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { hold.held.toList(seen) }

        hold.hold()
        hold.release()

        assertEquals(listOf(false, true, false), seen)
        assertEquals(false, hold.isHeld())
    }
}
