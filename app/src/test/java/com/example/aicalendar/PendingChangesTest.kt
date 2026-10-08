package com.example.aicalendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingChangesTest {
    private val remote = PlannerEvent(id = "e1", title = "Server title", startAtMillis = 1_000L, remote = true, courseName = "BIO 227")

    private fun change(op: PendingOp, seq: Long, event: PlannerEvent? = null) = PendingChange("e1", op, event, seq)

    @Test
    fun deleteWins() {
        val update = change(PendingOp.UPDATE, 1, remote)
        assertEquals(PendingOp.DELETE, PendingChanges.coalesce(update, change(PendingOp.DELETE, 2)).op)
        val merged = PendingChanges.coalesce(change(PendingOp.DELETE, 1), change(PendingOp.UPDATE, 2, remote))
        assertEquals(PendingOp.DELETE, merged.op)
        assertEquals(2L, merged.seq)
    }

    @Test
    fun doneOnTopOfUpdateKeepsEditsAndMarksDone() {
        val edited = remote.copy(title = "Edited")
        val merged = PendingChanges.coalesce(change(PendingOp.UPDATE, 1, edited), change(PendingOp.MARK_DONE, 2))
        assertEquals(PendingOp.UPDATE, merged.op)
        assertEquals("Edited", merged.event?.title)
        assertEquals(true, merged.event?.done)
        assertEquals(2L, merged.seq)
    }

    @Test
    fun laterUpdateReplacesEarlierDone() {
        val reopened = remote.copy(done = false, title = "Reopened")
        val merged = PendingChanges.coalesce(change(PendingOp.MARK_DONE, 1), change(PendingOp.UPDATE, 2, reopened))
        assertEquals(PendingOp.UPDATE, merged.op)
        assertEquals(false, merged.event?.done)
    }

    @Test
    fun overlayKeepsOfflineDoneAgainstStaleServerSnapshot() {
        val result = PendingChanges.overlay(listOf(remote), mapOf("e1" to change(PendingOp.MARK_DONE, 1)))
        assertEquals(true, result.single().done)
    }

    @Test
    fun overlayAppliesEditsButKeepsServerOnlyFields() {
        val edited = remote.copy(title = "Phone title", reminderOffsetsMinutes = emptyList(), courseName = "")
        val result = PendingChanges.overlay(listOf(remote), mapOf("e1" to change(PendingOp.UPDATE, 1, edited))).single()
        assertEquals("Phone title", result.title)
        assertEquals(emptyList<Int>(), result.reminderOffsetsMinutes)
        assertEquals("BIO 227", result.courseName)
    }

    @Test
    fun overlayDropsPendingDeletesAndIgnoresUnrelated() {
        val other = remote.copy(id = "e2")
        val result = PendingChanges.overlay(listOf(remote, other), mapOf("e1" to change(PendingOp.DELETE, 1)))
        assertEquals(listOf("e2"), result.map { it.id })
    }

    @Test
    fun jsonRoundTrip() {
        val original = change(PendingOp.UPDATE, 7, remote.copy(done = true)).copy(attempts = 3)
        assertEquals(original, PendingChange.fromJson(original.toJson()))
        assertNull(PendingChange.fromJson(org.json.JSONObject("""{"id":"e1","op":"UPDATE","seq":1}""")))
        assertNull(PendingChange.fromJson(org.json.JSONObject("""{"id":"e1","op":"BOGUS","seq":1}""")))
    }

    @Test
    fun seriesChangesRoundTripUnderTheirOwnKey() {
        val created = PendingChange(PendingChange.seriesKey("k1"), PendingOp.SERIES_CREATE, null, 4, payload = """{"title":"Trash"}""")
        assertEquals("series:k1", created.eventId)
        assertEquals(created, PendingChange.fromJson(created.toJson()))
        val deleted = PendingChange(PendingChange.seriesKey("k2"), PendingOp.SERIES_DELETE, null, 5, target = "s1", payload = "2026-10-23")
        assertEquals(deleted, PendingChange.fromJson(deleted.toJson()))
        assertTrue(PendingOp.SERIES_UPDATE.isSeries)
        assertEquals(false, PendingOp.UPDATE.isSeries)
    }

    @Test
    fun queuedSeriesDeleteHidesItsOccurrencesUntilDelivered() {
        fun occurrence(id: String, date: String, done: Boolean = false, series: String = "s1") =
            PlannerEvent(id = id, title = "Trash", startAtMillis = 1_000L, remote = true, seriesId = series, occurrenceDate = date, done = done)
        val snapshot = listOf(
            occurrence("a", "2026-10-16"),
            occurrence("b", "2026-10-23"),
            occurrence("c", "2026-10-30", done = true),
            occurrence("d", "2026-10-30", series = "other"),
            remote,
        )
        val fromDate = PendingChange("series:k", PendingOp.SERIES_DELETE, null, 1, target = "s1", payload = "2026-10-23")
        assertEquals(listOf("a", "c", "d", "e1"), PendingChanges.overlay(snapshot, mapOf(fromDate.eventId to fromDate)).map { it.id })
        // Without a date: every unfinished occurrence still ahead.
        val whole = fromDate.copy(payload = "")
        assertTrue(whole.removes(occurrence("x", "2026-10-16").copy(startAtMillis = 5_000L), nowMillis = 2_000L))
        assertEquals(false, whole.removes(occurrence("y", "2026-10-02").copy(startAtMillis = 1_000L), nowMillis = 2_000L))
    }
}
