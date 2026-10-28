package com.example.aicalendar

enum class EventCategory(
    val displayName: String,
    val colorArgb: Int,
) {
    AUTO("Automatic", 0xFF8D96A2.toInt()),
    SCHOOL("School", 0xFF4F8EF7.toInt()),
    MEDICAL("Medical", 0xFFF06A6A.toInt()),
    FINANCIAL("Financial", 0xFF4CAF7D.toInt()),
    HOUSING("Housing", 0xFFF0A44B.toInt()),
    TRAVEL("Travel", 0xFF40B7C4.toInt()),
    WORK("Work", 0xFFA77BD8.toInt()),
    PERSONAL("Personal", 0xFFD77FA1.toInt()),
    DELIVERY("Delivery", 0xFFA9744F.toInt()),
    GENERAL("General", 0xFF8D96A2.toInt());

    companion object {
        fun fromStored(value: String?): EventCategory =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: AUTO
    }
}

private fun keywordPattern(vararg values: String): Regex =
    Regex("(?<![a-z0-9])(?:" + values.joinToString("|") { Regex.escape(it) } + ")(?![a-z0-9])")

// Compiled once: resolvedCategory() runs for every event on every list recomposition.
private val medicalKeywords = keywordPattern(
    "clinic", "dental", "dentist", "doctor", "health", "hospital", "medical",
    "medication", "ortho", "pharmacy", "prescription", "therapy", "urgent care",
    "telehealth", "physical therapy",
)
private val schoolKeywords = keywordPattern(
    "university", "campus", "class", "course", "exam", "homework", "lab kit",
    "lecture", "quiz", "school", "semester", "student center", "syllabus",
    "eth 110",
)
private val schoolTitleKeywords = keywordPattern("test")
private val travelKeywords = keywordPattern(
    "airport", "boarding", "departure", "flight", "hotel", "itinerary",
    "rental car", "travel", "trip",
)
private val housingKeywords = keywordPattern(
    "apartment", "housing", "landlord", "lease", "move-out", "move out",
    "moving", "movers", "packing", "rent", "tenant", "utility",
)
private val financialKeywords = keywordPattern(
    "bank", "bill", "budget", "credit", "fee", "financial", "insurance",
    "invoice", "payment", "refund", "tax", "tuition",
)
private val workKeywords = keywordPattern(
    "coworker", "deadline", "interview", "job", "meeting", "office", "shift", "work",
)
private val personalKeywords = keywordPattern("anniversary", "birthday", "family", "personal")

fun PlannerEvent.resolvedCategory(): EventCategory {
    if (category != EventCategory.AUTO) return category
    // A delivery is filed by an explicit choice, and takes its category from that choice too.
    if (domain == EventDomain.DELIVERY) return EventCategory.DELIVERY

    val titleText = title.lowercase()
    val searchable = listOf(title, location, notes).joinToString(" ").lowercase()
    return when {
        medicalKeywords.containsMatchIn(searchable) -> EventCategory.MEDICAL
        schoolKeywords.containsMatchIn(searchable) || schoolTitleKeywords.containsMatchIn(titleText) -> EventCategory.SCHOOL
        travelKeywords.containsMatchIn(searchable) -> EventCategory.TRAVEL
        housingKeywords.containsMatchIn(searchable) -> EventCategory.HOUSING
        financialKeywords.containsMatchIn(searchable) -> EventCategory.FINANCIAL
        workKeywords.containsMatchIn(searchable) -> EventCategory.WORK
        personalKeywords.containsMatchIn(searchable) -> EventCategory.PERSONAL
        else -> EventCategory.GENERAL
    }
}
