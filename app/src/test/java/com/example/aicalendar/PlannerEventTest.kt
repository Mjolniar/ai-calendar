package com.example.aicalendar

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlannerEventTest {
    private val base = PlannerEvent(id = "a", title = "Appointment", startAtMillis = 1_000L)

    @Test
    fun explicitlyEmptyOffsetsSurviveReload() {
        val quiet = base.copy(reminderOffsetsMinutes = emptyList())
        assertEquals(emptyList<Int>(), PlannerEvent.fromJson(quiet.toJson()).reminderOffsetsMinutes)
    }

    @Test
    fun missingOffsetsFallBackToDefaults() {
        val json = base.copy(critical = true).toJson().apply { remove("reminderOffsetsMinutes") }
        assertEquals(listOf(1440, 0), PlannerEvent.fromJson(json).reminderOffsetsMinutes)
    }

    @Test
    fun roundTripKeepsEveryField() {
        val event = base.copy(
            critical = true, done = true, archived = true, category = EventCategory.MEDICAL,
            domain = EventDomain.LIFE, pointsPossible = 12.5, submissionTypes = listOf("online_upload"),
            allowedAttempts = 2, reminderOffsetsMinutes = listOf(60, 0), remote = true, source = "canvas",
        )
        assertEquals(event, PlannerEvent.fromJson(event.toJson()))
    }

    @Test
    fun malformedOffsetsAreDropped() {
        val offsets = parseReminderOffsets(JSONArray("""[60, "30", "oops", -1, 1.5, 600000, 60, null]"""))
        assertEquals(listOf(60, 30), offsets)
    }

    @Test
    fun enablingCriticalAddsEventTimeAlarm() {
        assertEquals(listOf(1440, 0), offsetsAfterCriticalChange(listOf(1440), wasCritical = false, nowCritical = true))
        assertEquals(listOf(1440, 0), offsetsAfterCriticalChange(emptyList(), wasCritical = false, nowCritical = true))
        assertEquals(listOf(120, 0), offsetsAfterCriticalChange(listOf(120), wasCritical = false, nowCritical = true))
        assertEquals(listOf(60, 0), offsetsAfterCriticalChange(listOf(60, 0), wasCritical = false, nowCritical = true))
    }

    @Test
    fun disablingCriticalOnlyRevertsTheDefault() {
        assertEquals(listOf(1440), offsetsAfterCriticalChange(listOf(1440, 0), wasCritical = true, nowCritical = false))
        assertEquals(listOf(60, 0), offsetsAfterCriticalChange(listOf(60, 0), wasCritical = true, nowCritical = false))
    }

    @Test
    fun unchangedCriticalKeepsCustomOffsets() {
        assertEquals(emptyList<Int>(), offsetsAfterCriticalChange(emptyList(), wasCritical = false, nowCritical = false))
        assertEquals(listOf(1440), offsetsAfterCriticalChange(null, wasCritical = false, nowCritical = false))
        assertEquals(listOf(1440, 0), offsetsAfterCriticalChange(null, wasCritical = false, nowCritical = true))
    }

    @Test
    fun validationMirrorsBridgeRules() {
        assertNull(base.validationError())
        assertNotNull(base.copy(title = "   ").validationError())
        assertNotNull(base.copy(title = "x".repeat(501)).validationError())
        assertNotNull(base.copy(notes = "x".repeat(10_001)).validationError())
        assertNotNull(base.copy(location = "x".repeat(1_001)).validationError())
        assertNotNull(base.copy(reminderOffsetsMinutes = listOf(-1)).validationError())
    }

    @Test
    fun alarmSignatureIgnoresCosmeticFields() {
        assertEquals(base.alarmSignature(), base.copy(notes = "changed", title = "Renamed").alarmSignature())
        assert(base.alarmSignature() != base.copy(startAtMillis = 2_000L).alarmSignature())
        assert(base.alarmSignature() != base.copy(done = true).alarmSignature())
    }

    @Test
    fun becomingADeliveryChangesTheAlarmSignature() {
        val reminder = base.copy(reminderOffsetsMinutes = listOf(1440))
        val delivery = reminder.copy(domain = EventDomain.DELIVERY)

        assertNotEquals(reminder.alarmSignature(), delivery.alarmSignature())
        // Lead times left on a delivery are not alerts, so changing them changes nothing.
        assertEquals(delivery.alarmSignature(), delivery.copy(reminderOffsetsMinutes = listOf(60, 0)).alarmSignature())
    }

    @Test
    fun deliveryCalendarAndCategorySurviveReload() {
        val delivery = base.copy(
            domain = EventDomain.DELIVERY,
            category = EventCategory.DELIVERY,
            reminderOffsetsMinutes = emptyList(),
        )

        assertEquals(delivery, PlannerEvent.fromJson(delivery.toJson()))
    }
}
