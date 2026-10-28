package com.example.aicalendar

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class KitchenTest {
    private val tue = LocalDate.of(2026, 10, 27)

    private fun item(id: String, name: String, status: String = "need", addedAt: String = "2026-10-20T15:00:00Z", doneAt: String? = null) =
        JSONObject().put("id", id).put("name", name).put("status", status).put("addedAt", addedAt).also { if (doneAt != null) it.put("doneAt", doneAt) }

    private fun day(date: LocalDate, dinner: String, recipe: String? = null, kind: String = "cook") =
        JSONObject().put("date", date.toString()).put("label", "x").put("dinner", dinner).put("kind", kind).also { if (recipe != null) it.put("recipe", recipe) }

    /** A Tuesday-to-Monday week starting on [start], one dinner a day. */
    private fun plan(start: LocalDate, dinners: List<String> = listOf("Salmon", "Jambalaya", "Burgers", "Chili", "BBQ chicken", "Black bean burgers", "Leftovers")) =
        JSONObject()
            .put("week", JSONObject().put("start", start.toString()).put("end", start.plusDays(6).toString()).put("title", "Meal Plan"))
            .put("status", "ordered")
            .put("tagline", "For 2 · make-ahead lunches")
            .put("meta", JSONArray().put("Ordered · delivery **Mon 4–6pm** · no substitutions"))
            .put("days", JSONArray(dinners.mapIndexed { i, dinner -> day(start.plusDays(i.toLong()), dinner, recipe = if (dinner == "Leftovers") null else "r$i", kind = if (dinner == "Leftovers") "leftovers" else "cook") }))
            .put("boxes", JSONArray().put(JSONObject().put("title", "Breakfast & lunch").put("items", JSONArray().put("Oatmeal").put("Turkey wraps"))))
            .put(
                "recipes",
                JSONArray(dinners.indices.filter { dinners[it] != "Leftovers" }.map { i ->
                    JSONObject().put("id", "r$i").put("title", dinners[i]).put("sub", "45 min").put("ingredients", "A, B").put("steps", JSONArray().put("One").put("Two"))
                }),
            )

    private fun snapshotJson(week: JSONObject? = plan(tue), nextWeek: JSONObject? = null) = JSONObject()
        .put("revision", 42)
        .put(
            "list",
            JSONArray()
                .put(item("k000000000001", "Aluminum foil").put("qty", "1 roll").put("source", "device").put("addedBy", "Pixel"))
                .put(item("k000000000002", "Salmon fillets", status = "ordered").put("orderRef", "Walmart #200000000001, Tue 11/3"))
                .put(item("k000000000003", "Bananas", status = "got", doneAt = "2026-10-26T18:00:00Z")),
        )
        .put(
            "pantry",
            JSONArray()
                .put(JSONObject().put("id", "p000000000001").put("name", "Black beans").put("section", "Canned & jarred").put("status", "have"))
                .put(JSONObject().put("id", "p000000000002").put("name", "Eggs").put("section", "Fridge & counter").put("status", "out").put("note", "Large")),
        )
        .put("week", week ?: JSONObject.NULL)
        .put("nextWeek", nextWeek ?: JSONObject.NULL)
        .put("notes", JSONObject().put("2026-10-27", JSONObject().put("cooked", "yes").put("rating", "loved").put("note", "Great").put("updatedAt", "2026-10-28T02:00:00Z")))

    // ------------------------------------------------------------------ parser

    @Test
    fun parsesAFullSnapshot() {
        val snapshot = KitchenParser.parseSnapshot(snapshotJson())
        assertEquals(42L, snapshot.revision)
        assertEquals(listOf("Aluminum foil", "Salmon fillets", "Bananas"), snapshot.list.map { it.name })
        assertEquals("1 roll", snapshot.list[0].qty)
        assertEquals(ListStatus.ORDERED, snapshot.list[1].status)
        assertEquals("Walmart #200000000001, Tue 11/3", snapshot.list[1].orderRef)
        assertEquals("2026-10-26T18:00:00Z", snapshot.list[2].doneAt)
        assertEquals(PantryStatus.OUT, snapshot.pantry[1].status)
        assertEquals("Large", snapshot.pantry[1].note)
        val week = snapshot.week!!
        assertEquals(tue, week.start)
        assertEquals(tue.plusDays(6), week.end)
        assertEquals(7, week.days.size)
        assertEquals("Salmon", week.recipeFor(week.days[0])!!.title)
        assertEquals(listOf("One", "Two"), week.recipeFor(week.days[0])!!.steps)
        assertNull("Leftovers nights have no recipe", week.recipeFor(week.days[6]))
        assertEquals(DinnerKind.LEFTOVERS, week.days[6].kind)
        assertEquals(listOf("Oatmeal", "Turkey wraps"), week.boxes.single().items)
        assertNull(snapshot.nextWeek)
        val note = snapshot.noteFor(tue)
        assertEquals(Cooked.YES, note.cooked)
        assertEquals(Rating.LOVED, note.rating)
        assertEquals("Cooked · Loved it", note.summary)
        assertTrue(snapshot.noteFor(tue.plusDays(1)).isEmpty)
    }

    @Test
    fun missingOptionalFieldsTakeTheirDefaults() {
        val json = JSONObject()
            .put("list", JSONArray().put(JSONObject().put("id", "k1").put("name", "Dish soap")))
            .put("pantry", JSONArray().put(JSONObject().put("id", "p1").put("name", "Rice")))
            .put("week", JSONObject().put("days", JSONArray().put(JSONObject().put("date", "2026-10-27").put("dinner", "Tacos"))))
        val snapshot = KitchenParser.parseSnapshot(json)
        assertEquals(0L, snapshot.revision)
        val item = snapshot.list.single()
        assertEquals(ListStatus.NEED, item.status)
        assertEquals("", item.qty)
        assertEquals("", item.orderRef)
        val shelf = snapshot.pantry.single()
        assertEquals(PANTRY_OTHER, shelf.section)
        assertEquals(PantryStatus.HAVE, shelf.status)
        val week = snapshot.week!!
        assertNull(week.start)
        assertEquals("", week.tagline)
        assertTrue(week.recipes.isEmpty())
        assertEquals("Tacos", week.days.single().dinner)
        assertEquals(DinnerKind.COOK, week.days.single().kind)
        assertTrue(snapshot.notes.isEmpty())
        assertNull(snapshot.nextWeek)
    }

    @Test
    fun nullFieldsReadAsEmptyNotAsTheWordNull() {
        val parsed = KitchenParser.parseListItem(JSONObject().put("id", "k1").put("name", "Milk").put("qty", JSONObject.NULL).put("note", JSONObject.NULL))!!
        assertEquals("", parsed.qty)
        assertEquals("", parsed.note)
        // A number is read as the text it is.
        assertEquals("2", KitchenParser.parseListItem(JSONObject().put("id", "k2").put("name", "Limes").put("qty", 2))!!.qty)
    }

    @Test
    fun aMalformedItemIsSkippedNotFatal() {
        val json = JSONObject()
            .put(
                "list",
                JSONArray()
                    .put(JSONObject().put("name", "No id"))
                    .put(JSONObject().put("id", "bad id!").put("name", "Bad id"))
                    .put(JSONObject().put("id", "k1").put("name", "   "))
                    .put(JSONObject().put("id", "k2").put("name", "Unknown status").put("status", "cancelled"))
                    .put("just a string")
                    .put(7)
                    .put(JSONObject().put("id", "k3").put("name", "Coffee")),
            )
            .put(
                "pantry",
                JSONArray()
                    .put(JSONObject().put("id", "p1").put("name", "Flour").put("status", "plenty"))
                    .put(JSONObject().put("id", "p2").put("name", "Sugar")),
            )
            .put(
                "week",
                JSONObject()
                    .put("days", JSONArray().put(JSONObject().put("date", "Tuesday").put("dinner", "?")).put(JSONObject().put("date", "2026-10-28").put("dinner", "Chili")))
                    .put("recipes", JSONArray().put(JSONObject().put("title", "No id")).put(JSONObject().put("id", "chili").put("title", "Chili")))
                    .put("meta", JSONArray().put("ok").put(5).put(JSONObject.NULL)),
            )
            .put("notes", JSONObject().put("not-a-date", JSONObject().put("cooked", "yes")).put("2026-10-28", JSONObject().put("cooked", "maybe").put("rating", "ok")))
        val snapshot = KitchenParser.parseSnapshot(json)
        assertEquals(listOf("Coffee"), snapshot.list.map { it.name })
        assertEquals(listOf("Sugar"), snapshot.pantry.map { it.name })
        assertEquals(listOf(LocalDate.of(2026, 10, 28)), snapshot.week!!.days.map { it.date })
        assertEquals(listOf("chili"), snapshot.week!!.recipes.map { it.id })
        assertEquals(listOf("ok"), snapshot.week!!.meta)
        assertEquals(setOf("2026-10-28"), snapshot.notes.keys)
        assertEquals(Cooked.NONE, snapshot.notes.getValue("2026-10-28").cooked)
        assertEquals(Rating.OK, snapshot.notes.getValue("2026-10-28").rating)
    }

    @Test
    fun anAnswerWithoutTheListOrPantryIsAnError() {
        listOf(JSONObject(), JSONObject().put("list", JSONArray()), JSONObject().put("pantry", JSONArray())).forEach { json ->
            try {
                KitchenParser.parseSnapshot(json)
                fail("$json must not read as an empty kitchen")
            } catch (expected: MalformedKitchenException) {
                // A proxy page or an old bridge, never "nothing on the list".
            }
        }
    }

    @Test
    fun ingredientsMayBeALineListAndStepsASingleString() {
        val recipe = KitchenParser.parseRecipe(
            JSONObject().put("id", "r").put("title", "Chili").put("ingredients", JSONArray().put("1 lb turkey").put("2 cans beans")).put("steps", "Simmer 30 min"),
        )!!
        assertEquals("1 lb turkey\n2 cans beans", recipe.ingredients)
        assertEquals(listOf("Simmer 30 min"), recipe.steps)
    }

    @Test
    fun writeAnswersAreRead() {
        val created = KitchenParser.parseItemAnswer(JSONObject().put("revision", 7).put("created", true).put("item", item("k1", "Foil")))
        assertEquals(7L, created.revision)
        assertTrue(created.created)
        assertFalse(KitchenParser.parseItemAnswer(JSONObject().put("revision", 8).put("created", false).put("item", item("k1", "Foil"))).created)
        val pantry = KitchenParser.parsePantryAnswer(
            JSONObject().put("revision", 9)
                .put("item", JSONObject().put("id", "p1").put("name", "Eggs").put("status", "out"))
                .put("listItem", item("k9", "Eggs").put("source", "pantry")),
        )
        assertEquals(PantryStatus.OUT, pantry.item.status)
        assertEquals("pantry", pantry.listItem!!.source)
        assertNull(KitchenParser.parsePantryAnswer(JSONObject().put("item", JSONObject().put("id", "p1").put("name", "Eggs").put("status", "low"))).listItem)
        val note = KitchenParser.parseNoteAnswer(JSONObject().put("revision", 10).put("note", JSONObject().put("rating", "no")), "2026-10-27")
        assertEquals("2026-10-27", note.date)
        assertEquals(Rating.NO, note.note.rating)
        try {
            KitchenParser.parseItemAnswer(JSONObject().put("revision", 1))
            fail("an answer without its item is unreadable")
        } catch (expected: MalformedKitchenException) {
        }
    }

    // ------------------------------------------------------------------ which day is today

    @Test
    fun tonightIsTheDayOfThePhonesDate() {
        val snapshot = KitchenParser.parseSnapshot(snapshotJson())
        val thursday = tue.plusDays(2)
        val plan = KitchenDays.currentPlan(snapshot, thursday)!!
        assertEquals("Burgers", KitchenDays.tonight(plan, thursday)!!.dinner)
        val others = KitchenDays.otherDays(plan, thursday)
        assertEquals(6, others.size)
        assertFalse(others.any { it.date == thursday })
        assertEquals(others.sortedBy { it.date }, others)
    }

    @Test
    fun todayIsTheLocalDateNotTheUtcOne() {
        // 9:30 PM on Tuesday in Denver is already Wednesday in UTC.
        val evening = Instant.parse("2026-10-28T03:30:00Z")
        assertEquals(LocalDate.of(2026, 10, 27), KitchenDays.today(ZoneId.of("America/Denver"), evening))
        assertEquals(LocalDate.of(2026, 10, 28), KitchenDays.today(ZoneId.of("Europe/Berlin"), evening))
        val snapshot = KitchenParser.parseSnapshot(snapshotJson())
        val today = KitchenDays.today(ZoneId.of("America/Denver"), evening)
        assertEquals("Salmon", KitchenDays.tonight(KitchenDays.currentPlan(snapshot, today), today)!!.dinner)
    }

    @Test
    fun noDinnerTonightOutsideThePlannedWeek() {
        val snapshot = KitchenParser.parseSnapshot(snapshotJson())
        val monday = tue.minusDays(1)
        val plan = KitchenDays.currentPlan(snapshot, monday)
        assertSame("still the current week, shown without a Tonight", snapshot.week, plan)
        assertNull(KitchenDays.tonight(plan, monday))
        assertEquals(7, KitchenDays.otherDays(plan!!, monday).size)
        assertNull(KitchenDays.tonight(null, monday))
    }

    @Test
    fun theNextWeekTakesOverOnceTodayIsInIt() {
        val next = tue.plusDays(7)
        val snapshot = KitchenParser.parseSnapshot(snapshotJson(nextWeek = plan(next, listOf("Tacos", "Soup", "Pasta", "Stir fry", "Pizza", "Curry", "Leftovers"))))
        // While this week runs, next week is listed after it.
        assertSame(snapshot.week, KitchenDays.currentPlan(snapshot, tue.plusDays(3)))
        assertSame(snapshot.nextWeek, KitchenDays.followingPlan(snapshot, snapshot.week))
        // The phone's date has moved on before the cloud's snapshot did.
        val plan = KitchenDays.currentPlan(snapshot, next)
        assertSame(snapshot.nextWeek, plan)
        assertEquals("Tacos", KitchenDays.tonight(plan, next)!!.dinner)
        assertNull(KitchenDays.followingPlan(snapshot, plan))
        // No current week published, only the next one.
        val onlyNext = KitchenParser.parseSnapshot(snapshotJson(week = null, nextWeek = plan(next)))
        assertSame(onlyNext.nextWeek, KitchenDays.currentPlan(onlyNext, tue))
        assertNull(KitchenDays.currentPlan(KitchenParser.parseSnapshot(snapshotJson(week = null)), tue))
    }

    // ------------------------------------------------------------------ **bold**

    @Test
    fun boldIsSplitIntoRuns() {
        assertEquals(
            listOf(KitchenText.Run("Ordered · delivery ", false), KitchenText.Run("Mon 4–6pm", true), KitchenText.Run(" · no substitutions", false)),
            KitchenText.boldRuns("Ordered · delivery **Mon 4–6pm** · no substitutions"),
        )
        assertEquals(listOf(KitchenText.Run("plain", false)), KitchenText.boldRuns("plain"))
        assertEquals(listOf(KitchenText.Run("a", true), KitchenText.Run(" and ", false), KitchenText.Run("b", true)), KitchenText.boldRuns("**a** and **b**"))
        assertTrue(KitchenText.boldRuns("").isEmpty())
    }

    @Test
    fun boldMarkersAreStripped() {
        assertEquals("Ordered · delivery Mon 4–6pm · no substitutions", KitchenText.stripBold("Ordered · delivery **Mon 4–6pm** · no substitutions"))
        assertEquals("Thaw 1 lb chicken tonight", KitchenText.stripBold("Thaw **1 lb chicken** tonight"))
        // An unpaired marker is text, and a single asterisk is left alone.
        assertEquals("2 ** 3", KitchenText.stripBold("2 ** 3"))
        assertEquals("bold then **open", KitchenText.stripBold("**bold** then **open"))
        assertEquals("5* rating", KitchenText.stripBold("5* rating"))
        assertEquals("", KitchenText.stripBold("****"))
    }

    // ------------------------------------------------------------------ order and returned items

    @Test
    fun aTickedItemMovesToTheTopOfGotIt() {
        val snapshot = KitchenParser.parseSnapshot(snapshotJson())
        val foil = snapshot.list.first { it.name == "Aluminum foil" }
        val ticked = foil.copy(status = ListStatus.GOT, doneAt = "2026-10-27T20:00:00Z")
        val after = snapshot.withListItem(ticked, revision = 43)
        assertEquals(listOf("Salmon fillets", "Aluminum foil", "Bananas"), after.list.map { it.name })
        assertEquals(43L, after.revision)
        // An older revision never lowers the one held.
        assertEquals(43L, after.withoutListItem(ticked.id, revision = 40).revision)
    }

    @Test
    fun listOrderIsNeedOrderedThenGotNewestFirst() {
        val items = listOf(
            KitchenListItem("k1", "Old got", status = ListStatus.GOT, doneAt = "2026-10-20T10:00:00Z"),
            KitchenListItem("k2", "Second need", addedAt = "2026-10-22T10:00:00Z"),
            KitchenListItem("k3", "Ordered", status = ListStatus.ORDERED, addedAt = "2026-10-19T10:00:00Z"),
            KitchenListItem("k4", "First need", addedAt = "2026-10-21T10:00:00Z"),
            KitchenListItem("k5", "New got", status = ListStatus.GOT, doneAt = "2026-10-26T10:00:00Z"),
        )
        assertEquals(listOf("First need", "Second need", "Ordered", "New got", "Old got"), KitchenOrder.list(items).map { it.name })
    }

    @Test
    fun gotItKeepsTheLastSevenDays() {
        val now = Instant.parse("2026-10-27T12:00:00Z")
        val items = listOf(
            KitchenListItem("k1", "Yesterday", status = ListStatus.GOT, doneAt = "2026-10-26T12:00:00Z"),
            KitchenListItem("k2", "Eight days ago", status = ListStatus.GOT, doneAt = "2026-10-19T11:00:00Z"),
            KitchenListItem("k3", "No date", status = ListStatus.GOT),
            KitchenListItem("k4", "Still needed"),
        )
        assertEquals(listOf("Yesterday", "No date"), KitchenOrder.recentGot(items, now).map { it.name })
    }

    @Test
    fun tiesKeepTheOrderTheBridgeStoredThem() {
        val same = "2026-10-21T10:00:00Z"
        val items = listOf(
            KitchenListItem("k9", "Zucchini", addedAt = same),
            KitchenListItem("k1", "apples", addedAt = same),
            KitchenListItem("k5", "Milk", status = ListStatus.GOT, doneAt = same),
            KitchenListItem("k2", "Bread", status = ListStatus.GOT, doneAt = same),
        )
        assertEquals("not by name, not by id", listOf("k9", "k1", "k5", "k2"), KitchenOrder.list(items).map { it.id })
        // An item put back in place keeps its place among its ties; a new one comes last.
        val snapshot = KitchenSnapshot(1, KitchenOrder.list(items), emptyList(), null, null, emptyMap())
        assertEquals(listOf("k9", "k1"), snapshot.withListItem(items[0].copy(qty = "2"), 2).openItems.map { it.id })
        assertEquals(listOf("k9", "k1", "k3"), snapshot.withListItem(KitchenListItem("k3", "Apples too", addedAt = same), 2).openItems.map { it.id })
    }

    @Test
    fun aGotItemWithoutDoneAtCountsFromItsLastChange() {
        val now = Instant.parse("2026-10-27T12:00:00Z")
        val items = listOf(
            KitchenListItem("k1", "Changed yesterday", status = ListStatus.GOT, updatedAt = "2026-10-26T12:00:00Z"),
            KitchenListItem("k2", "Changed 9 days ago", status = ListStatus.GOT, updatedAt = "2026-10-18T12:00:00Z"),
            KitchenListItem("k3", "No date at all", status = ListStatus.GOT),
        )
        assertEquals(listOf("Changed yesterday", "No date at all"), KitchenOrder.recentGot(items, now).map { it.name })
        assertEquals("2026-10-26T12:00:00Z", KitchenOrder.gotAt(items[0]))
        // In order a missing doneAt sorts last, as the bridge sorts it.
        val ordered = KitchenOrder.list(items + KitchenListItem("k4", "Bought", status = ListStatus.GOT, doneAt = "2026-10-20T12:00:00Z"))
        assertEquals("k4", ordered.first().id)
    }

    @Test
    fun sectionsAreOneWhateverTheirCase() {
        val pantry = KitchenOrder.pantry(
            listOf(PantryItem("p1", "Peas", section = "Freezer"), PantryItem("p2", "Corn", section = "freezer"), PantryItem("p3", "Rice", section = "Grains")),
        )
        val sections = KitchenOrder.pantrySections(pantry)
        assertEquals(2, sections.size)
        assertEquals(listOf("Corn", "Peas"), sections.first().second.map { it.name })
        assertEquals(setOf("Peas", "Corn"), KitchenOrder.estimateSections(pantry).first().second.map { it.name }.toSet())
    }

    @Test
    fun theBridgesDateIsReadAndKept() {
        val snapshot = KitchenParser.parseSnapshot(snapshotJson().put("today", "2026-10-28"))
        assertEquals(LocalDate.of(2026, 10, 28), snapshot.today)
        assertEquals(snapshot, KitchenParser.parseSnapshot(JSONObject(KitchenCacheJson.write(snapshot).toString())))
        // Bridge 0.10.0 sends none; a broken one is ignored.
        assertNull(KitchenParser.parseSnapshot(snapshotJson()).today)
        assertNull(KitchenParser.parseSnapshot(snapshotJson().put("today", "Wednesday")).today)
        // Tonight still goes by the phone's own date.
        val thursday = tue.plusDays(2)
        assertEquals("Burgers", KitchenDays.tonight(KitchenDays.currentPlan(snapshot, thursday), thursday)!!.dinner)
    }

    @Test
    fun onlyANewerSnapshotReplacesTheOneShown() {
        // Nothing shown yet, or an answer without a revision: taken.
        assertTrue(KitchenRevisions.accepts(null, -1, 5))
        assertTrue(KitchenRevisions.accepts(5, 5, 0))
        // The revision already fetched: nothing changed, not re-rendered.
        assertFalse(KitchenRevisions.accepts(5, 5, 5))
        // Older than an edit applied since (shown 8 after this phone's edit, fetched 5): a late answer.
        assertFalse(KitchenRevisions.accepts(8, 5, 7))
        // At the revision of the phone's own edit: taken, it carries what the server did besides.
        assertTrue(KitchenRevisions.accepts(8, 5, 8))
        assertTrue(KitchenRevisions.accepts(8, 5, 9))
    }

    @Test
    fun offlineRetriesBackOffToFiveMinutes() {
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L), (0..5).map(KitchenRetry::delayMillis))
        assertEquals(300_000L, KitchenRetry.delayMillis(40))
    }

    @Test
    fun pantryIsGroupedBySectionInOrder() {
        val pantry = KitchenOrder.pantry(
            listOf(
                PantryItem("p1", "Peas", section = "Freezer"),
                PantryItem("p2", "black beans", section = "Canned & jarred"),
                PantryItem("p3", "Ketchup", section = "Sauces & condiments"),
                PantryItem("p4", "Corn", section = "Canned & jarred"),
            ),
        )
        val sections = KitchenOrder.pantrySections(pantry)
        assertEquals(listOf("Canned & jarred", "Freezer", "Sauces & condiments"), sections.map { it.first })
        assertEquals(listOf("black beans", "Corn"), sections.first().second.map { it.name })
    }

    // ------------------------------------------------------------------ the client

    private class Recorder(private val answer: (String, String, JSONObject?) -> JSONObject = { _, _, _ -> JSONObject() }) {
        val calls = mutableListOf<Triple<String, String, JSONObject?>>()
        val client = KitchenClient { method, path, body ->
            calls += Triple(method, path, body)
            answer(method, path, body)
        }
    }

    @Test
    fun theClientSendsTheSpecsRoutes() {
        val recorder = Recorder { method, path, _ ->
            when {
                path == "/v1/kitchen" -> snapshotJson()
                path.startsWith("/v1/kitchen/items") && method != "DELETE" -> JSONObject().put("revision", 2).put("created", true).put("item", item("k000000000001", "Dish soap"))
                path.startsWith("/v1/kitchen/pantry") -> JSONObject().put("revision", 3).put("item", JSONObject().put("id", "p000000000001").put("name", "Eggs").put("status", "out"))
                path.startsWith("/v1/kitchen/notes") -> JSONObject().put("revision", 4).put("date", "2026-10-27").put("note", JSONObject().put("rating", "loved"))
                else -> JSONObject().put("revision", 5).put("deleted", true)
            }
        }
        val client = recorder.client
        assertTrue(client.load() is KitchenResult.Ok)
        assertTrue(client.addItem("  Dish   soap ", qty = " 2 ") is KitchenResult.Ok)
        assertTrue(client.setItemStatus("k000000000001", ListStatus.GOT) is KitchenResult.Ok)
        assertEquals(KitchenResult.Ok(5L), client.removeItem("k000000000001"))
        assertTrue(client.setPantryStatus("p000000000001", PantryStatus.OUT) is KitchenResult.Ok)
        val saved = client.saveNote(tue, DinnerNote(Cooked.YES, Rating.LOVED, " Great "))
        assertEquals(Rating.LOVED, (saved as KitchenResult.Ok).value.note.rating)
        assertEquals(
            listOf(
                "GET" to "/v1/kitchen",
                "POST" to "/v1/kitchen/items",
                "PATCH" to "/v1/kitchen/items/k000000000001",
                "DELETE" to "/v1/kitchen/items/k000000000001",
                "PATCH" to "/v1/kitchen/pantry/p000000000001",
                "PUT" to "/v1/kitchen/notes/2026-10-27",
            ),
            recorder.calls.map { it.first to it.second },
        )
        val add = recorder.calls[1].third!!
        assertEquals("Dish soap", add.getString("name"))
        assertEquals("2", add.getString("qty"))
        assertFalse("an empty note is not sent", add.has("note"))
        assertEquals("got", recorder.calls[2].third!!.getString("status"))
        assertNull(recorder.calls[3].third)
        assertEquals("out", recorder.calls[4].third!!.getString("status"))
        val note = recorder.calls[5].third!!
        assertEquals("yes", note.getString("cooked"))
        assertEquals("loved", note.getString("rating"))
        assertEquals("Great", note.getString("note"))
    }

    @Test
    fun theClientRefusesWhatTheServerWouldWithoutCallingIt() {
        val recorder = Recorder()
        assertTrue(recorder.client.addItem("   ") is KitchenResult.Failed)
        assertTrue(recorder.client.addItem("x".repeat(121)) is KitchenResult.Failed)
        assertTrue(recorder.client.setItemStatus("k1", ListStatus.ORDERED) is KitchenResult.Failed)
        assertTrue(recorder.client.setItemStatus("../events", ListStatus.GOT) is KitchenResult.Failed)
        assertTrue(recorder.client.removeItem("k1/../../x") is KitchenResult.Failed)
        assertTrue(recorder.calls.isEmpty())
    }

    @Test
    fun failuresSayWhatHappened() {
        fun failing(error: Exception) = Recorder { _, _, _ -> throw error }.client
        val offline = failing(IOException("Unable to resolve host")).setItemStatus("k1", ListStatus.GOT) as KitchenResult.Failed
        assertEquals(KITCHEN_OFFLINE, offline.message)
        assertTrue(offline.connection)
        val unpaired = failing(IllegalStateException("Phone is not paired")).load() as KitchenResult.Failed
        assertEquals(KITCHEN_OFFLINE, unpaired.message)
        assertTrue(unpaired.connection)
        assertEquals(KITCHEN_OFFLINE, (failing(BridgeHttpException(503, "Unavailable")).load() as KitchenResult.Failed).message)
        assertTrue((failing(BridgeHttpException(401, "Unauthorized device")).load() as KitchenResult.Failed).connection)
        val gone = failing(BridgeHttpException(404, "Not found")).removeItem("k1") as KitchenResult.Failed
        assertTrue(gone.notFound)
        val noKitchen = failing(BridgeHttpException(404, "Bridge returned HTTP 404")).load() as KitchenResult.Failed
        assertFalse(noKitchen.notFound)
        assertTrue(noKitchen.message.contains("doesn't offer Kitchen"))
        val rejected = failing(BridgeHttpException(400, "name is too long")).addItem("Foil") as KitchenResult.Failed
        assertEquals("name is too long", rejected.message)
        assertFalse(rejected.connection)
        val garbled = Recorder { _, _, _ -> JSONObject().put("hello", "portal") }.client.load() as KitchenResult.Failed
        assertTrue(garbled.connection)
        assertNotNull(garbled.message)
    }
}
