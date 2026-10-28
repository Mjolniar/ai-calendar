package com.example.aicalendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class EventBucketsTest {
    @Test
    fun passedEventsMoveToArchiveAndCurrentEventsStayChronological() {
        val events = listOf(
            event("later", 3_000),
            event("oldest", 500),
            event("soon", 2_000),
            event("recent", 999),
        )

        val buckets = bucketEvents(events, nowMillis = 1_000)

        assertEquals(listOf("soon", "later"), buckets.current.map { it.id })
        assertEquals(listOf("recent", "oldest"), buckets.archived.map { it.id })
    }

    @Test
    fun eventAtCurrentTimeRemainsCurrent() {
        val buckets = bucketEvents(listOf(event("now", 1_000)), nowMillis = 1_000)

        assertEquals(listOf("now"), buckets.current.map { it.id })
        assertEquals(emptyList<PlannerEvent>(), buckets.archived)
    }

    @Test
    fun completedAndManuallyArchivedFutureEventsShareArchive() {
        val events = listOf(
            event("active", 2_000),
            event("completed", 3_000, done = true),
            event("manual", 4_000, archived = true),
        )

        val buckets = bucketEvents(events, nowMillis = 1_000)

        assertEquals(listOf("active"), buckets.current.map { it.id })
        assertEquals(listOf("manual", "completed"), buckets.archived.map { it.id })
    }

    private val denver = ZoneId.of("America/Denver")

    private fun october(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(denver).toInstant().toEpochMilli()

    @Test
    fun deliveryStaysCurrentThroughItsExpectedDay() {
        val delivery = event("lamp", october(6, 9)).copy(domain = EventDomain.DELIVERY)
        val reminder = event("rent", october(6, 9))

        val afternoon = bucketEvents(listOf(delivery, reminder), nowMillis = october(6, 15), zone = denver)
        assertEquals(listOf("lamp"), afternoon.current.map { it.id })
        assertEquals(listOf("rent"), afternoon.archived.map { it.id })

        val lastMinute = bucketEvents(listOf(delivery), nowMillis = october(6, 23, 59), zone = denver)
        assertEquals(listOf("lamp"), lastMinute.current.map { it.id })

        val nextDay = bucketEvents(listOf(delivery), nowMillis = october(7, 0), zone = denver)
        assertEquals(emptyList<PlannerEvent>(), nextDay.current)
        assertEquals(listOf("lamp"), nextDay.archived.map { it.id })
    }

    @Test
    fun aDeliveryChosenByCategoryGetsTheSameDay() {
        val delivery = event("lamp", october(6, 9)).copy(category = EventCategory.DELIVERY)

        assertFalse(delivery.isFiledAt(october(6, 20), denver))
        assertTrue(delivery.isFiledAt(october(7, 0, 1), denver))
        assertEquals(october(7, 0) - 1, delivery.currentUntilMillis(denver))
    }

    @Test
    fun receivedOrArchivedDeliveriesAreFiledStraightAway() {
        val received = event("lamp", october(6, 9), done = true).copy(domain = EventDomain.DELIVERY)
        val archived = event("desk", october(6, 9), archived = true).copy(domain = EventDomain.DELIVERY)

        val buckets = bucketEvents(listOf(received, archived), nowMillis = october(1, 12), zone = denver)

        assertEquals(emptyList<PlannerEvent>(), buckets.current)
        assertEquals(setOf("lamp", "desk"), buckets.archived.map { it.id }.toSet())
    }

    @Test
    fun birthdayStaysCurrentUntilTheDayAfter() {
        val birthday = event("priya", october(3, 9)).copy(title = "Priya's birthday")
        val errand = event("errand", october(3, 9)).copy(title = "Pick up the cake")

        // 9:01 AM on the day: the errand is filed, the birthday is not.
        val morning = bucketEvents(listOf(birthday, errand), nowMillis = october(3, 9, 1), zone = denver)
        assertEquals(listOf("priya"), morning.current.map { it.id })
        assertEquals(listOf("errand"), morning.archived.map { it.id })

        val lastMinute = bucketEvents(listOf(birthday), nowMillis = october(3, 23, 59), zone = denver)
        assertEquals(listOf("priya"), lastMinute.current.map { it.id })

        val dayAfter = bucketEvents(listOf(birthday), nowMillis = october(4, 0), zone = denver)
        assertEquals(emptyList<PlannerEvent>(), dayAfter.current)
        assertEquals(listOf("priya"), dayAfter.archived.map { it.id })

        // Before the day it is an ordinary upcoming reminder; once done it is filed at once.
        assertFalse(birthday.isFiledAt(october(1, 12), denver))
        assertTrue(birthday.copy(done = true).isFiledAt(october(1, 12), denver))
        assertTrue(birthday.copy(archived = true).isFiledAt(october(3, 10), denver))
    }

    @Test
    fun aBirthdayIsRecognizedByItsTitleOnly() {
        assertTrue(isBirthdayTitle("Priya's birthday"))
        assertTrue(isBirthdayTitle("Jordan Alex Rivera's Birthday"))
        assertTrue(isBirthdayTitle("BIRTHDAY: Dad turns 60"))
        assertTrue(isBirthdayTitle("Kids' birthdays this month"))
        assertTrue(isBirthdayTitle("Sam's b-day dinner"))
        assertTrue(isBirthdayTitle("Sam bday"))
        assertFalse(isBirthdayTitle("Pay rent"))
        assertFalse(isBirthdayTitle("Birthdaycake order"))
        assertFalse(isBirthdayTitle("Rebirthday"))

        // Notes and place do not make a reminder a birthday.
        val gift = event("gift", october(3, 9)).copy(title = "Order flowers", notes = "For Priya's birthday", location = "Birthday Shop")
        assertFalse(gift.isBirthday())
        assertFalse(gift.isListedAllDay())
        assertTrue(event("b", october(3, 9)).copy(title = "Priya's birthday").isListedAllDay())
        assertTrue(event("d", october(3, 9)).copy(domain = EventDomain.DELIVERY).isListedAllDay())
    }

    private fun on(month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, month, day, hour, minute).atZone(denver).toInstant().toEpochMilli()

    @Test
    fun archiveRunsFromTheLatestBackwardsAndKeepsEarlyOnesForLast() {
        // Saturday, October 3, 11:42 AM.
        val now = on(10, 3, 11, 42)
        val events = listOf(
            event("exam-in-december", on(12, 13, 23, 59), done = true),
            event("class-next-week", on(10, 6, 15), done = true),
            event("tonight-done-early", on(10, 3, 20), done = true),
            event("parcel-this-morning", on(10, 3, 10), done = true),
            event("quiz-last-night", on(10, 2, 23, 59), done = true),
            event("plumber-yesterday", on(10, 2, 9)),
            event("move-out", on(9, 30, 9)),
            event("august", on(8, 15, 9), done = true),
            event("still-ahead", on(10, 5, 9)),
        )

        val buckets = bucketEvents(events.shuffled(java.util.Random(7)), nowMillis = now, zone = denver)

        assertEquals(listOf("still-ahead"), buckets.current.map { it.id })
        assertEquals(
            listOf(
                // Today and back: the latest first.
                "tonight-done-early", "parcel-this-morning", "quiz-last-night", "plumber-yesterday", "move-out", "august",
                // Not due yet: the soonest first.
                "class-next-week", "exam-in-december",
            ),
            buckets.archived.map { it.id },
        )
    }

    @Test
    fun archivedRemindersAtTheSameMomentAreInTitleOrder() {
        val moment = on(9, 27, 23, 59)
        val events = listOf(
            event("3", moment, done = true).copy(title = "chapter 04 Quiz"),
            event("1", moment, done = true).copy(title = "Reflection"),
            event("2", moment, done = true).copy(title = "Case Study"),
            event("4", moment, done = true).copy(title = "Case Study"),
        )

        val archived = bucketEvents(events, nowMillis = on(10, 3, 12), zone = denver).archived

        assertEquals(listOf("Case Study", "Case Study", "chapter 04 Quiz", "Reflection"), archived.map { it.title })
        assertEquals(listOf("2", "4", "3", "1"), archived.map { it.id })
    }

    @Test
    fun deliveryDayEndsCorrectlyWhenTheClocksChange() {
        // Daylight saving time ends on November 1, 2026 in Denver: that day is 25 hours long.
        val start = LocalDateTime.of(2026, 11, 1, 9, 0).atZone(denver).toInstant().toEpochMilli()
        val nextMidnight = LocalDateTime.of(2026, 11, 2, 0, 0).atZone(denver).toInstant().toEpochMilli()

        assertEquals(nextMidnight - 1, currentUntilMillis(start, allDay = true, zone = denver))
        assertEquals(start, currentUntilMillis(start, allDay = false, zone = denver))
    }

    private fun event(
        id: String,
        startAtMillis: Long,
        done: Boolean = false,
        archived: Boolean = false,
    ) = PlannerEvent(
        id = id,
        title = id,
        startAtMillis = startAtMillis,
        done = done,
        archived = archived,
    )
}
