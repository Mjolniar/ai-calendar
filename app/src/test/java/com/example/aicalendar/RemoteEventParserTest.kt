package com.example.aicalendar

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteEventParserTest {
    @Test
    fun oneMalformedEventDoesNotDropTheRest() {
        val response = JSONObject(
            """
            {"revision": 3, "events": [
              {"id":"good","title":"Dentist","startAt":"2026-10-01T15:00:00.000Z","critical":true,"reminderOffsetsMinutes":[1440,0]},
              {"id":"bad-date","title":"Broken","startAt":"not a date"},
              {"id":"blank","title":"  ","startAt":"2026-10-01T15:00:00Z"},
              "not an object",
              {"title":"no id","startAt":"2026-10-01T15:00:00Z"}
            ]}
            """.trimIndent(),
        )
        val snapshot = RemoteEventParser.parseSnapshot(response)
        assertEquals(listOf("good"), snapshot.events.map { it.id })
        assertEquals(setOf("bad-date", "blank"), snapshot.unparsedIds)
        assertEquals(4, snapshot.skippedCount)
        assertTrue(snapshot.events.single().remote)
    }

    @Test
    fun aDeliveryFromTheBridgeIsFiledUnderDeliveriesWithoutAlerts() {
        val event = RemoteEventParser.parseEvent(
            JSONObject(
                """{"id":"lamp","title":"Desk lamp arrives","startAt":"2026-10-06T15:00:00Z","domain":"delivery",
                   "category":"automatic","critical":true,"reminderOffsetsMinutes":[]}""",
            ),
        )
        assertEquals(EventDomain.DELIVERY, event.domain)
        assertEquals(EventCategory.AUTO, event.category)
        assertEquals(EventCategory.DELIVERY, event.resolvedCategory())
        assertTrue(event.isDelivery())
        assertEquals(emptyList<Int>(), event.alertOffsets())
    }

    @Test(expected = MalformedSnapshotException::class)
    fun missingEventsArrayIsAnErrorNotAnEmptyList() {
        RemoteEventParser.parseSnapshot(JSONObject("""{"error":"proxy"}"""))
    }

    @Test
    fun nullsBecomeEmptyAndEmptyOffsetsArePreserved() {
        val event = RemoteEventParser.parseEvent(
            JSONObject(
                """{"id":"x","title":"T","startAt":"2026-10-01T15:00:00Z","location":null,"notes":null,
                   "pointsPossible":null,"allowedAttempts":"3","reminderOffsetsMinutes":[]}""",
            ),
        )
        assertEquals("", event.location)
        assertEquals("", event.notes)
        assertEquals(null, event.pointsPossible)
        assertEquals(3, event.allowedAttempts)
        assertEquals(emptyList<Int>(), event.reminderOffsetsMinutes)
        assertEquals("chatgpt", event.source)
    }

    @Test
    fun absentOffsetsUseCriticalAwareDefaults() {
        val event = RemoteEventParser.parseEvent(
            JSONObject("""{"id":"x","title":"T","startAt":"2026-10-01T15:00:00Z","critical":true}"""),
        )
        assertEquals(listOf(1440, 0), event.reminderOffsetsMinutes)
    }
}
