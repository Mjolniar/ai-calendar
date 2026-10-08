package com.example.aicalendar

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class RepeatRuleTest {
    private val denver = ZoneId.of("America/Denver")
    private val friday = LocalDate.of(2026, 10, 9)

    private fun rule(
        frequency: RepeatFrequency,
        interval: Int = 1,
        weekdays: List<Int> = emptyList(),
        start: LocalDate = friday,
        time: LocalTime = LocalTime.of(8, 0),
        until: LocalDate? = null,
        count: Int? = null,
    ) = RepeatRule(frequency, interval, weekdays, start, time, until, count)

    @Test
    fun ruleInWords() {
        assertEquals("Every week on Friday at 8:00 AM", RepeatText.describe(rule(RepeatFrequency.WEEKLY)))
        assertEquals(
            "Every 2 weeks on Monday, Wednesday and Friday at 5:30 PM",
            RepeatText.describe(rule(RepeatFrequency.WEEKLY, 2, listOf(5, 1, 3), time = LocalTime.of(17, 30))),
        )
        assertEquals("Every day at 8:00 AM", RepeatText.describe(rule(RepeatFrequency.DAILY)))
        assertEquals("Every 3 days at 8:00 AM, 10 times", RepeatText.describe(rule(RepeatFrequency.DAILY, 3, count = 10)))
        assertEquals("Every month on the 31st at 8:00 AM", RepeatText.describe(rule(RepeatFrequency.MONTHLY, start = LocalDate.of(2026, 1, 31))))
        assertEquals("Every 6 months on the 2nd at 12:00 PM, 1 time", RepeatText.describe(rule(RepeatFrequency.MONTHLY, 6, start = LocalDate.of(2026, 1, 2), time = LocalTime.NOON, count = 1)))
        assertEquals(
            "Every year on October 9 at 8:00 AM, until Dec 4, 2030",
            RepeatText.describe(rule(RepeatFrequency.YEARLY, until = LocalDate.of(2030, 12, 4))),
        )
        assertEquals(listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "23rd", "31st"),
            listOf(1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 31).map(RepeatText::ordinal))
    }

    @Test
    fun seriesParsesFromSyncAndSurvivesStorage() {
        val response = JSONObject(
            """
            {"revision": 5, "events": [], "series": [
              {"id":"s1","title":"Trash pickup","timeZone":"America/Denver","domain":"life","category":"housing",
               "critical":false,"reminderOffsetsMinutes":[900],"skippedDates":["2026-10-16"],
               "rule":{"freq":"weekly","interval":1,"weekdays":[5],"startDate":"2026-10-09","time":"08:00","count":9}},
              {"id":"bad","title":"Broken","timeZone":"UTC","rule":{"freq":"hourly","startDate":"2026-10-09","time":"08:00"}}
            ]}
            """.trimIndent(),
        )
        val series = ReminderSeries.parseList(response)!!
        assertEquals(listOf("s1"), series.map { it.id })
        val trash = series.single()
        assertEquals(RepeatFrequency.WEEKLY, trash.rule.frequency)
        assertEquals(listOf(5), trash.rule.weekdays)
        assertEquals(9, trash.rule.count)
        assertEquals(listOf(900), trash.reminderOffsetsMinutes)
        assertEquals(trash, ReminderSeries.fromJson(trash.toJson()))
        assertNull(ReminderSeries.parseList(JSONObject("""{"events": []}""")))
    }

    @Test
    fun occurrenceFieldsParseAndPersist() {
        val event = RemoteEventParser.parseEvent(
            JSONObject("""{"id":"o1","title":"Trash","startAt":"2026-10-09T14:00:00.000Z","seriesId":"s1","occurrenceDate":"2026-10-09","detached":true}"""),
        )
        assertTrue(event.isOccurrence)
        assertEquals("2026-10-09", event.occurrenceDate)
        assertTrue(event.detached)
        assertEquals(event, PlannerEvent.fromJson(event.toJson()))
        val plain = RemoteEventParser.parseEvent(JSONObject("""{"id":"p1","title":"Dentist","startAt":"2026-10-09T14:00:00Z"}"""))
        assertFalse(plain.isOccurrence)
        assertFalse(PlannerEvent.fromJson(plain.toJson()).isOccurrence)
    }

    @Test
    fun choiceValidationAndRule() {
        val weekly = RepeatChoice(RepeatFrequency.WEEKLY)
        assertNull(weekly.validationError(friday))
        // A weekly pattern with no day chosen falls on the first date's weekday.
        assertEquals(listOf(5), weekly.rule(friday, LocalTime.of(8, 0)).weekdays)
        assertEquals("Repeat every 1 to 999 weeks.", weekly.copy(interval = 0).validationError(friday))
        assertEquals("Choose the date it ends.", weekly.copy(end = RepeatEnd.ON_DATE).validationError(friday))
        assertEquals("It cannot end before its first date.", weekly.copy(end = RepeatEnd.ON_DATE, until = friday.minusDays(1)).validationError(friday))
        assertEquals("End after 1 to 1000 times.", weekly.copy(end = RepeatEnd.AFTER, count = 0).validationError(friday))
        // Only the chosen end is kept.
        val rule = weekly.copy(end = RepeatEnd.NEVER, until = friday, count = 4).rule(friday, LocalTime.of(8, 0))
        assertNull(rule.until)
        assertNull(rule.count)
        val back = RepeatChoice.from(rule(RepeatFrequency.MONTHLY, 2, count = 6))
        assertEquals(RepeatEnd.AFTER, back.end)
        assertEquals(6, back.count)
    }

    private fun event(date: LocalDate, time: LocalTime) = PlannerEvent(
        id = "o1",
        title = " Trash pickup ",
        startAtMillis = LocalDateTime.of(date, time).atZone(denver).toInstant().toEpochMilli(),
        reminderOffsetsMinutes = listOf(900),
        domain = EventDomain.LIFE,
        category = EventCategory.HOUSING,
        remote = true,
        seriesId = "s1",
        occurrenceDate = date.toString(),
    )

    @Test
    fun createBody() {
        val body = SeriesRequests.create(event(friday, LocalTime.of(8, 0)), RepeatChoice(RepeatFrequency.WEEKLY, count = 9, end = RepeatEnd.AFTER), denver, "key-0001")
        assertEquals("Trash pickup", body.getString("title"))
        assertEquals("America/Denver", body.getString("timeZone"))
        assertEquals("life", body.getString("domain"))
        assertEquals("housing", body.getString("category"))
        assertEquals("[900]", body.getJSONArray("reminderOffsetsMinutes").toString())
        assertEquals("key-0001", body.getString("idempotencyKey"))
        val rule = body.getJSONObject("rule")
        assertEquals("weekly", rule.getString("freq"))
        assertEquals("2026-10-09", rule.getString("startDate"))
        assertEquals("08:00", rule.getString("time"))
        assertEquals("[5]", rule.getJSONArray("weekdays").toString())
        assertEquals(9, rule.getInt("count"))
        assertFalse(rule.has("until"))
    }

    @Test
    fun allFutureBodyKeepsTheAnchorUnlessTheDayMoved() {
        val occurrence = event(LocalDate.of(2026, 10, 23), LocalTime.of(8, 0))
        val choice = RepeatChoice(RepeatFrequency.WEEKLY, weekdays = listOf(5))
        val sameDay = SeriesRequests.update(occurrence.copy(startAtMillis = occurrence.startAtMillis + 30 * 60_000L), occurrence, choice, denver, "key-0002")
        assertEquals("2026-10-23", sameDay.getString("from"))
        val rule = sameDay.getJSONObject("rule")
        assertFalse(rule.has("startDate"))
        assertEquals("08:30", rule.getString("time"))
        assertTrue(rule.isNull("until"))
        assertTrue(rule.isNull("count"))

        val moved = SeriesRequests.update(event(LocalDate.of(2026, 10, 24), LocalTime.of(8, 0)), occurrence, choice.copy(weekdays = listOf(6)), denver, "key-0003")
        assertEquals("2026-10-23", moved.getString("from"))
        assertEquals("2026-10-24", moved.getJSONObject("rule").getString("startDate"))
        assertEquals("[6]", moved.getJSONObject("rule").getJSONArray("weekdays").toString())
    }
}
