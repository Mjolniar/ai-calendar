package com.example.aicalendar

enum class EventDomain(val displayName: String) {
    AUTO("Automatic"),
    LIFE("Life"),
    SCHOOL("School"),
    DELIVERY("Deliveries");

    companion object {
        fun fromStored(value: String?): EventDomain =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: AUTO
    }
}

fun PlannerEvent.resolvedDomain(): EventDomain = when {
    domain != EventDomain.AUTO -> domain
    category == EventCategory.DELIVERY -> EventDomain.DELIVERY
    resolvedCategory() == EventCategory.SCHOOL -> EventDomain.SCHOOL
    else -> EventDomain.LIFE
}

/**
 * A package, order or shipment on its way: listed on the Deliveries tab, and it never alerts.
 *
 * Only an explicit choice (the Deliveries calendar or the Delivery category) files a reminder here.
 * The wording is never used to guess, because a wrong guess would silence a real reminder.
 */
fun PlannerEvent.isDelivery(): Boolean =
    domain == EventDomain.DELIVERY || (domain == EventDomain.AUTO && category == EventCategory.DELIVERY)

/** The lead times that actually alert: none for a delivery, whatever is stored on it. */
fun PlannerEvent.alertOffsets(): List<Int> = if (isDelivery()) emptyList() else reminderOffsetsMinutes
