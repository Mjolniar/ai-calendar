package com.example.aicalendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDomainTest {
    @Test
    fun legacySchoolCategoriesMigrateIntoSchoolFeed() {
        val event = PlannerEvent(
            title = "Final exam",
            startAtMillis = 1L,
            category = EventCategory.SCHOOL,
        )

        assertEquals(EventDomain.SCHOOL, event.resolvedDomain())
    }

    @Test
    fun explicitDomainOverridesCategoryInference() {
        val event = PlannerEvent(
            title = "Campus medical appointment",
            startAtMillis = 1L,
            category = EventCategory.MEDICAL,
            domain = EventDomain.SCHOOL,
        )

        assertEquals(EventDomain.SCHOOL, event.resolvedDomain())
    }

    @Test
    fun ordinaryLegacyRemindersRemainInLifeFeed() {
        val event = PlannerEvent(title = "Pay rent", startAtMillis = 1L)

        assertEquals(EventDomain.LIFE, event.resolvedDomain())
    }

    @Test
    fun deliveriesAreFiledByChoiceNeverByWording() {
        val chosen = PlannerEvent(title = "Desk lamp", startAtMillis = 1L, domain = EventDomain.DELIVERY)
        val byCategory = PlannerEvent(title = "Desk lamp", startAtMillis = 1L, category = EventCategory.DELIVERY)
        val worded = PlannerEvent(title = "Package delivery arrives, UPS shipment", startAtMillis = 1L)

        assertEquals(EventDomain.DELIVERY, chosen.resolvedDomain())
        assertEquals(EventDomain.DELIVERY, byCategory.resolvedDomain())
        assertEquals(EventDomain.LIFE, worded.resolvedDomain())
        assertTrue(chosen.isDelivery())
        assertTrue(byCategory.isDelivery())
        assertFalse(worded.isDelivery())
    }

    @Test
    fun anExplicitCalendarBeatsTheDeliveryCategory() {
        val event = PlannerEvent(
            title = "Print the return label",
            startAtMillis = 1L,
            category = EventCategory.DELIVERY,
            domain = EventDomain.LIFE,
        )

        assertEquals(EventDomain.LIFE, event.resolvedDomain())
        assertFalse(event.isDelivery())
    }

    @Test
    fun deliveryCheckAgreesWithTheResolvedCalendar() {
        for (domain in EventDomain.entries) {
            for (category in EventCategory.entries) {
                val event = PlannerEvent(title = "Quiz kit delivery", startAtMillis = 1L, domain = domain, category = category)
                assertEquals("$domain/$category", event.resolvedDomain() == EventDomain.DELIVERY, event.isDelivery())
            }
        }
    }

    @Test
    fun deliveriesNeverAlertWhateverIsStoredOnThem() {
        val reminder = PlannerEvent(
            title = "Desk lamp",
            startAtMillis = 1L,
            critical = true,
            reminderOffsetsMinutes = listOf(1440, 60, 0),
        )

        assertEquals(listOf(1440, 60, 0), reminder.alertOffsets())
        assertEquals(emptyList<Int>(), reminder.copy(domain = EventDomain.DELIVERY).alertOffsets())
        assertEquals(emptyList<Int>(), reminder.copy(category = EventCategory.DELIVERY).alertOffsets())
    }

    @Test
    fun theBridgeNameForDeliveriesIsUnderstood() {
        assertEquals(EventDomain.DELIVERY, EventDomain.fromStored("delivery"))
        assertEquals(EventDomain.DELIVERY, EventDomain.fromStored("DELIVERY"))
        assertEquals(EventCategory.DELIVERY, EventCategory.fromStored("delivery"))
    }
}
