package com.example.aicalendar

import org.junit.Assert.assertEquals
import org.junit.Test

class EventCategoryTest {
    @Test
    fun automaticallyClassifiesCommonReminderTypes() {
        assertEquals(EventCategory.SCHOOL, event("ETH 110 Ethics class").resolvedCategory())
        assertEquals(EventCategory.SCHOOL, event("Math test").resolvedCategory())
        assertEquals(EventCategory.MEDICAL, event("VA clinic appointment").resolvedCategory())
        assertEquals(EventCategory.FINANCIAL, event("Tuition payment deadline").resolvedCategory())
        assertEquals(EventCategory.HOUSING, event("Move-out inspection").resolvedCategory())
        assertEquals(EventCategory.TRAVEL, event("Flight boarding time").resolvedCategory())
        assertEquals(EventCategory.GENERAL, event("Call Alex").resolvedCategory())
    }

    @Test
    fun explicitCategoryOverridesAutomaticClassification() {
        val reminder = event("School payment").copy(category = EventCategory.PERSONAL)

        assertEquals(EventCategory.PERSONAL, reminder.resolvedCategory())
    }

    @Test
    fun doesNotClassifyWordsByPartialKeywordMatches() {
        assertEquals(EventCategory.GENERAL, event("Coffee with Alex").resolvedCategory())
        assertEquals(EventCategory.WORK, event("Parent meeting at the office").resolvedCategory())
        assertEquals(EventCategory.TRAVEL, event("Rental car pickup").resolvedCategory())
        assertEquals(
            EventCategory.GENERAL,
            event("Confirm replacement timing").copy(notes = "Test the replacement before returning it").resolvedCategory(),
        )
    }

    @Test
    fun deliveryIsNeverGuessedFromWording() {
        assertEquals(EventCategory.GENERAL, event("Package delivery arrives").resolvedCategory())
        assertEquals(EventCategory.MEDICAL, event("Prescription delivery from the pharmacy").resolvedCategory())
    }

    @Test
    fun deliveriesTakeTheDeliveryCategoryUnlessGivenAnother() {
        val delivery = event("Prescription from the pharmacy").copy(domain = EventDomain.DELIVERY)

        assertEquals(EventCategory.DELIVERY, delivery.resolvedCategory())
        assertEquals(EventCategory.MEDICAL, delivery.copy(category = EventCategory.MEDICAL).resolvedCategory())
    }

    private fun event(title: String) = PlannerEvent(title = title, startAtMillis = 1L)
}
