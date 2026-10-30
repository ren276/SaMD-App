package com.example.samdapp.presentation.home

import com.example.samdapp.R
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** [CopyRef.resolve] against a recording fake of [CopyText], so what reaches Android is pinned
 *  without Android. Before FU-1 a string with no arguments called `getString(res)`; FU-1 sent it
 *  through the `String.format` overload, and nothing noticed. */
class CopyRefResolveTest {

    private class RecordingText : CopyText {
        val calls = mutableListOf<List<Any>>()

        override fun string(res: Int, args: List<Any>): String {
            calls += listOf("string", res, args)
            return "s$res$args"
        }

        override fun plural(res: Int, count: Int, args: List<Any>): String {
            calls += listOf("plural", res, count, args)
            return "p$res:$count$args"
        }
    }

    // Written out by hand: every string the caption and the snackbar read with no arguments.
    private val countFree = listOf(
        R.string.sync_caption_sending,
        R.string.sync_caption_offline_activity_log,
        R.string.sync_caption_offline_nothing_else,
        R.string.sync_caption_offline_all_sent,
        R.string.sync_caption_owner_mismatch,
        R.string.sync_caption_activity_log,
        R.string.sync_caption_up_to_date,
        R.string.sync_now_offline,
        R.string.sync_now_still_trying,
        R.string.sync_now_all_sent,
        R.string.sync_now_failed_no_connection,
        R.string.sync_now_failed_sign_in,
        R.string.sync_now_failed_server_refused,
        R.string.sync_now_failed_local_store,
        R.string.sync_now_failed_local_network,
    )

    @Test
    fun `every count-free string reaches the no-args path`() {
        assertEquals(15, countFree.size)
        countFree.forEach { res ->
            val fake = RecordingText()
            CopyRef.Str(res).resolve(fake)
            assertEquals("$res", listOf(listOf("string", res, emptyList<Any>())), fake.calls)
        }
    }

    @Test
    fun `a string with arguments passes them unchanged and in order`() {
        val fake = RecordingText()
        CopyRef.Str(R.string.sync_caption_waiting_records_and_doctor_cases, listOf(3, 2)).resolve(fake)
        assertEquals(listOf(listOf("string", R.string.sync_caption_waiting_records_and_doctor_cases, listOf(3, 2))), fake.calls)
    }

    @Test
    fun `a plural passes its count and the count again as the format argument`() {
        val fake = RecordingText()
        val text = CopyRef.Plural(R.plurals.sync_caption_doctor_cases, 4).resolve(fake)
        assertEquals(listOf(listOf("plural", R.plurals.sync_caption_doctor_cases, 4, listOf(4))), fake.calls)
        assertEquals("p${R.plurals.sync_caption_doctor_cases}:4[4]", text)
    }

    @Test
    fun `in the nested caption the inner plurals resolve first and their text is the outer argument`() {
        val fake = RecordingText()
        val text = SyncCaption.NeedsReviewWithHeld(review = 5, held = 6).copyRef().resolve(fake)
        val review = "p${R.plurals.sync_caption_needs_review_count}:5[5]"
        val held = "p${R.plurals.sync_caption_held_count}:6[6]"
        assertEquals(
            listOf(
                listOf("plural", R.plurals.sync_caption_needs_review_count, 5, listOf(5)),
                listOf("plural", R.plurals.sync_caption_held_count, 6, listOf(6)),
                listOf("string", R.string.sync_caption_needs_review_with_held, listOf(review, held)),
            ),
            fake.calls,
        )
        assertEquals("s${R.string.sync_caption_needs_review_with_held}[$review, $held]", text)
    }

    @Test
    fun `the overload helper takes the plain path for no arguments and the formatted path otherwise`() {
        assertEquals("plain", stringVia(emptyList(), plain = { "plain" }, formatted = { "formatted" }))
        var seen: Array<Any>? = null
        assertEquals("formatted", stringVia(listOf(1, "b"), plain = { "plain" }, formatted = { seen = it; "formatted" }))
        assertArrayEquals(arrayOf<Any>(1, "b"), seen)
    }
}
