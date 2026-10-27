package com.example.samdapp.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.samdapp.data.local.dao.DeviceDataOwnerDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * The owner marker's claim, read and clear (facility-scope memo 3.1) against real SQLite, in a
 * file database of its own (never the app's), so concurrent claims go through Room's real
 * connection pool rather than one in-memory connection.
 */
class DeviceDataOwnerDaoTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val testDbName = "device-data-owner-test.db"
    private lateinit var db: AppDatabase
    private lateinit var dao: DeviceDataOwnerDao

    @Before
    fun setUp() {
        context.deleteDatabase(testDbName)
        db = Room.databaseBuilder(context, AppDatabase::class.java, testDbName).build()
        dao = db.deviceDataOwnerDao()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(testDbName)
    }

    private val at = Instant.ofEpochMilli(1_761_000_000_000)

    private suspend fun claim(facility: String, worker: String = "w-$facility", legacy: Boolean = false) =
        dao.claim(facility, "PHC $facility", at, worker, legacy)

    private fun ownerRows(): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM device_data_owner").use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun aClaimOnAPhoneWithNoOwnerWritesEveryField() = runBlocking {
        assertNull("no owner before the first claim", dao.read())

        assertTrue(claim("fac-a", worker = "w-1", legacy = true))

        val owner = dao.read()
        assertNotNull("the claimed owner is readable", owner)
        owner!!
        assertEquals("fac-a", owner.facilityId)
        assertEquals("PHC fac-a", owner.facilityName)
        assertEquals(at, owner.claimedAt)
        assertEquals("w-1", owner.claimedByWorkerId)
        assertTrue("the legacy flag round-trips", owner.legacy)
    }

    @Test
    fun aSecondClaimLosesAndChangesNothing() = runBlocking {
        assertTrue(claim("fac-a"))

        assertFalse("another facility cannot claim an owned phone", claim("fac-b"))
        assertFalse("nor can the owner claim it twice", claim("fac-a"))

        assertEquals("one owner row, never two", 1, ownerRows())
        assertEquals("the first owner stands", "fac-a", dao.read()?.facilityId)
    }

    /**
     * The race made certain rather than left to the scheduler: a second connection holds the write
     * lock while every claimant starts, so every claimant gets past any read before any insert can
     * run. An atomic claim still lets exactly one win; a read-then-insert lets all of them win.
     * The lock is held well under SQLite's busy timeout, so the waiting inserts proceed.
     */
    @Test
    fun ofConcurrentClaimsExactlyOneWins() = runBlocking {
        repeat(ROUNDS) { round ->
            dao.clear()
            val blocker = SQLiteDatabase.openDatabase(
                context.getDatabasePath(testDbName).path, null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
            )
            blocker.beginTransactionNonExclusive()
            val claims = (1..CLAIMANTS).map { n -> async(Dispatchers.IO) { "fac-$n" to claim("fac-$n") } }
            delay(LOCK_HELD_MS)
            blocker.endTransaction()
            blocker.close()
            val winners = claims.awaitAll().filter { it.second }.map { it.first }

            assertEquals("round $round: exactly one claim wins, got $winners", 1, winners.size)
            assertEquals("round $round: the stored owner is the winner", winners.single(), dao.read()?.facilityId)
            assertEquals("round $round: one owner row", 1, ownerRows())
        }
    }

    @Test
    fun clearRemovesTheOwnerAndANewClaimSucceeds() = runBlocking {
        assertTrue(claim("fac-a"))

        dao.clear()

        assertNull("no owner after the clear", dao.read())
        assertEquals(0, ownerRows())
        assertTrue("the phone can be claimed again", claim("fac-b"))
        assertEquals("fac-b", dao.read()?.facilityId)
    }

    private companion object {
        const val ROUNDS = 5
        const val CLAIMANTS = 8
        const val LOCK_HELD_MS = 500L
    }
}
