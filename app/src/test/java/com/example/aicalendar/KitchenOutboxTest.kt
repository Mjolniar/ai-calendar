package com.example.aicalendar

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class KitchenOutboxTest {
    private val storage = InMemoryOutboxStorage()
    private val outbox = KitchenOutbox(storage)
    private val at = "2026-10-27T18:00:00Z"

    private fun status(id: String, status: String, label: String = id) = outbox.enqueue(KitchenOp.ITEM_STATUS, id, mapOf("status" to status), label, at)!!
    private fun add(name: String, id: String = newLocalKitchenId()) = outbox.enqueue(KitchenOp.ADD_ITEM, id, mapOf("name" to name), name, at)!!

    // ------------------------------------------------------------------ the queue

    @Test
    fun editsQueueInOrderAndSurviveARestart() {
        val first = status("k000000000001", "got")
        val second = add("Paper towels")
        val third = outbox.enqueue(KitchenOp.NOTE, "2026-10-27", mapOf("rating" to "loved"), "Dinner", at)!!
        assertTrue(first.seq < second.seq && second.seq < third.seq)
        // A new outbox over the same storage is what the app finds after it was closed.
        val reopened = KitchenOutbox(InMemoryOutboxStorage(storage.text))
        assertEquals(listOf(first, second, third), reopened.all())
        assertEquals(first, reopened.first())
        // Numbers keep growing after a restart, so the order holds.
        assertTrue(reopened.enqueue(KitchenOp.REMOVE_ITEM, "k000000000002")!!.seq > third.seq)
    }

    @Test
    fun anUnreadableQueueStartsEmpty() {
        val broken = KitchenOutbox(InMemoryOutboxStorage("{not json"))
        assertTrue(broken.isEmpty())
        val unknownOp = KitchenOutbox(InMemoryOutboxStorage("""{"next":3,"edits":[{"seq":1,"op":"teleport","target":"k1"},{"seq":2,"op":"remove_item","target":"k2"}]}"""))
        assertEquals(listOf("k2"), unknownOp.all().map { it.target })
    }

    @Test
    fun removingAnItemThatNeverLeftThePhoneDropsItsAdd() {
        val local = newLocalKitchenId()
        add("Lemons", local)
        status(local, "got")
        val other = status("k000000000001", "need")
        assertNull("nothing is queued for the cloud", outbox.enqueue(KitchenOp.REMOVE_ITEM, local))
        assertEquals(listOf(other), outbox.all())
        // The same for the pantry.
        val shelf = newLocalKitchenId()
        outbox.enqueue(KitchenOp.PANTRY_ADD, shelf, mapOf("name" to "Rice"))
        assertNull(outbox.enqueue(KitchenOp.PANTRY_REMOVE, shelf))
        assertEquals(listOf(other), outbox.all())
    }

    @Test
    fun aSentAddGivesLaterEditsItsRealId() {
        val local = newLocalKitchenId()
        val added = add("Lemons", local)
        status(local, "got")
        status("k000000000001", "got")
        assertTrue(outbox.sent(added, "k0000000000aa"))
        assertEquals(listOf("k0000000000aa", "k000000000001"), outbox.all().map { it.target })
        // A screen still showing the local id reaches the real item.
        assertEquals("k0000000000aa", outbox.resolve(local))
        assertEquals("k0000000000aa", outbox.enqueue(KitchenOp.REMOVE_ITEM, local)!!.target)
        // And after a restart.
        assertEquals("k0000000000aa", KitchenOutbox(InMemoryOutboxStorage(storage.text)).resolve(local))
    }

    @Test
    fun aDroppedAddTakesItsEditsWithIt() {
        val local = newLocalKitchenId()
        val added = add("x".repeat(10), local)
        status(local, "got")
        val other = status("k000000000001", "got")
        outbox.drop(added)
        assertEquals(listOf(other), outbox.all())
    }

    // ------------------------------------------------------------------ sending

    /** A bridge that answers each route with [answer], recording the calls. */
    private class Bridge(val answer: (method: String, path: String, body: JSONObject?) -> JSONObject) {
        val calls = mutableListOf<String>()
        val client = KitchenClient { method, path, body ->
            calls += "$method $path"
            answer(method, path, body)
        }
    }

    private fun ok(method: String, path: String, body: JSONObject?): JSONObject = when {
        method == "POST" && path == "/v1/kitchen/items" ->
            JSONObject().put("revision", 2).put("created", true).put("item", JSONObject().put("id", "k0000000000aa").put("name", body!!.getString("name")))
        method == "POST" && path == "/v1/kitchen/pantry" ->
            JSONObject().put("revision", 2).put("created", true).put("item", JSONObject().put("id", "p0000000000aa").put("name", body!!.getString("name")))
        method == "PATCH" && path.startsWith("/v1/kitchen/items/") ->
            JSONObject().put("revision", 3).put("item", JSONObject().put("id", path.substringAfterLast('/')).put("name", "x").put("status", body!!.getString("status")))
        method == "PATCH" ->
            JSONObject().put("revision", 3).put("item", JSONObject().put("id", path.substringAfterLast('/')).put("name", "x").put("status", body!!.getString("status")))
        method == "PUT" -> JSONObject().put("revision", 4).put("note", JSONObject())
        else -> JSONObject().put("revision", 5).put("deleted", true)
    }

    @Test
    fun theQueueIsSentOldestFirstWithRealIds() {
        val local = newLocalKitchenId()
        status("k000000000001", "got")
        add("Lemons", local)
        status(local, "got")
        outbox.enqueue(KitchenOp.PANTRY_STATUS, "p000000000001", mapOf("status" to "out"))
        outbox.enqueue(KitchenOp.NOTE, "2026-10-27", mapOf("cooked" to "yes", "rating" to "loved", "note" to ""))
        outbox.enqueue(KitchenOp.REMOVE_ITEM, "k000000000002")
        val bridge = Bridge(::ok)
        val report = KitchenFlusher.flush(outbox, bridge.client)
        assertEquals(
            listOf(
                "PATCH /v1/kitchen/items/k000000000001",
                "POST /v1/kitchen/items",
                "PATCH /v1/kitchen/items/k0000000000aa",
                "PATCH /v1/kitchen/pantry/p000000000001",
                "PUT /v1/kitchen/notes/2026-10-27",
                "DELETE /v1/kitchen/items/k000000000002",
            ),
            bridge.calls,
        )
        assertEquals(KitchenFlushReport(sent = 6), report)
        assertTrue(outbox.isEmpty())
    }

    @Test
    fun aGoneItemIsDroppedSilentlyAndARefusedOneWithItsName() {
        status("k000000000001", "got", label = "Aluminum foil")
        add("Paper towels")
        status("k000000000003", "got", label = "Dish soap")
        val bridge = Bridge { method, path, body ->
            when (path) {
                "/v1/kitchen/items/k000000000001" -> throw BridgeHttpException(404, "Item not found")
                "/v1/kitchen/items" -> throw BridgeHttpException(400, "name is too long")
                else -> ok(method, path, body)
            }
        }
        val report = KitchenFlusher.flush(outbox, bridge.client)
        assertEquals(1, report.sent)
        assertEquals(1, report.gone)
        assertEquals(listOf("Paper towels: name is too long"), report.rejected)
        assertFalse(report.stopped)
        assertTrue(outbox.isEmpty())
        assertEquals(3, bridge.calls.size)
    }

    @Test
    fun aNetworkErrorOr5xxKeepsTheEditAndStops() {
        listOf(IOException("Unable to resolve host"), BridgeHttpException(503, "Unavailable")).forEach { failure ->
            val storage = InMemoryOutboxStorage()
            val outbox = KitchenOutbox(storage)
            outbox.enqueue(KitchenOp.ITEM_STATUS, "k000000000001", mapOf("status" to "got"))
            val waiting = outbox.enqueue(KitchenOp.ITEM_STATUS, "k000000000002", mapOf("status" to "got"))!!
            outbox.enqueue(KitchenOp.REMOVE_ITEM, "k000000000003")
            val bridge = Bridge { method, path, body ->
                if (path.endsWith("k000000000002")) throw failure else ok(method, path, body)
            }
            val report = KitchenFlusher.flush(outbox, bridge.client)
            assertTrue(report.stopped)
            assertEquals(1, report.sent)
            assertEquals("the failed edit waits at the front", waiting, outbox.first())
            assertEquals(2, outbox.size())
            assertEquals("nothing after it was tried", 2, bridge.calls.size)
        }
    }

    @Test
    fun aRefusedAddTakesTheEditsToItsItemWithIt() {
        val local = newLocalKitchenId()
        add("Bad name", local)
        status(local, "got")
        status("k000000000001", "got")
        val bridge = Bridge { method, path, body -> if (method == "POST") throw BridgeHttpException(400, "name is required") else ok(method, path, body) }
        val report = KitchenFlusher.flush(outbox, bridge.client)
        assertEquals(listOf("POST /v1/kitchen/items", "PATCH /v1/kitchen/items/k000000000001"), bridge.calls)
        assertEquals(1, report.rejected.size)
        assertTrue(outbox.isEmpty())
    }

    @Test
    fun anItemRemovedWhileItsAddWasOnTheWayIsRemovedInTheCloudToo() {
        val local = newLocalKitchenId()
        add("Lemons", local)
        val bridge = Bridge { method, path, body ->
            // The person removes it on the phone while the add is being sent.
            if (method == "POST") outbox.enqueue(KitchenOp.REMOVE_ITEM, local)
            ok(method, path, body)
        }
        KitchenFlusher.flush(outbox, bridge.client)
        assertEquals(listOf("POST /v1/kitchen/items", "DELETE /v1/kitchen/items/k0000000000aa"), bridge.calls)
        assertTrue(outbox.isEmpty())
    }

    @Test
    fun aPantryAddAnswerSaysWhetherItWasNew() {
        val existing = KitchenParser.parsePantryAnswer(
            JSONObject().put("revision", 3).put("created", false).put("item", JSONObject().put("id", "p1").put("name", "Rice").put("section", "Grains")),
        )
        assertFalse(existing.created)
        assertTrue(KitchenParser.parsePantryAnswer(JSONObject().put("item", JSONObject().put("id", "p1").put("name", "Rice"))).created)
    }

    // ------------------------------------------------------------------ what the phone shows meanwhile

    private val base = KitchenSnapshot(
        revision = 9,
        list = listOf(
            KitchenListItem("k000000000001", "Aluminum foil", addedAt = "2026-10-20T10:00:00Z"),
            KitchenListItem("k000000000002", "Eggs", addedAt = "2026-10-21T10:00:00Z"),
        ),
        pantry = listOf(
            PantryItem("p000000000001", "Black beans", "Canned & jarred"),
            PantryItem("p000000000002", "Eggs", "Fridge & counter", PantryStatus.OUT),
        ),
        week = null,
        nextWeek = null,
        notes = emptyMap(),
    )

    @Test
    fun queuedEditsAreShownAtOnce() {
        val lemons = newLocalKitchenId()
        add("Lemons", lemons)
        add("aluminum  FOIL")
        status("k000000000002", "got")
        outbox.enqueue(KitchenOp.REMOVE_ITEM, "k000000000001")
        val rice = newLocalKitchenId()
        outbox.enqueue(KitchenOp.PANTRY_ADD, rice, mapOf("name" to "Rice", "section" to "", "status" to "low"))
        outbox.enqueue(KitchenOp.PANTRY_STATUS, "p000000000001", mapOf("status" to "out"))
        outbox.enqueue(KitchenOp.NOTE, "2026-10-27", mapOf("cooked" to "skipped", "rating" to "", "note" to "Ate out"))
        val shown = KitchenOverlay.apply(base, outbox.all())
        assertEquals("open items: the new one, not a second foil", listOf("Lemons"), shown.openItems.map { it.name })
        assertEquals(lemons, shown.openItems.single().id)
        assertEquals(ListStatus.GOT, shown.list.first { it.name == "Eggs" }.status)
        assertEquals("Eggs restocked (rule 3)", PantryStatus.HAVE, shown.pantry.first { it.name == "Eggs" }.status)
        assertEquals(PantryStatus.OUT, shown.pantry.first { it.name == "Black beans" }.status)
        val added = shown.pantry.first { it.name == "Rice" }
        assertEquals(PANTRY_OTHER, added.section)
        assertEquals(PantryStatus.LOW, added.status)
        assertEquals(Cooked.SKIPPED, shown.noteFor(LocalDate.of(2026, 10, 27)).cooked)
        assertEquals(9L, shown.revision)
        // A pantry item removed while offline goes too; the base itself is untouched.
        outbox.enqueue(KitchenOp.PANTRY_REMOVE, "p000000000001")
        assertTrue(KitchenOverlay.apply(base, outbox.all()).pantry.none { it.name == "Black beans" })
        assertEquals(2, base.list.size)
    }

    @Test
    fun theOfflineBarSaysWhenAndHowMany() {
        val zone = ZoneId.of("America/Denver")
        val now = Instant.parse("2026-10-27T18:30:00Z")
        val fetched = Instant.parse("2026-10-27T15:41:00Z")
        assertEquals(
            "Offline: showing 9:41 AM. 2 changes will be sent when you're back online.",
            KitchenOverlay.offlineText(fetched, 2, zone, now),
        )
        assertEquals(
            "Offline: showing 9:41 AM. 1 change will be sent when you're back online.",
            KitchenOverlay.offlineText(fetched, 1, zone, now),
        )
        assertEquals(
            "Offline: showing Oct 26, 9:41 AM. Changes will be sent when you're back online.",
            KitchenOverlay.offlineText(fetched.minusSeconds(86_400), 0, zone, now),
        )
    }

    @Test
    fun theCachedSnapshotReadsBackTheSame() {
        val start = LocalDate.of(2026, 10, 27)
        val json = JSONObject()
            .put("revision", 12)
            .put("list", JSONArray().put(JSONObject().put("id", "k1").put("name", "Foil").put("qty", "1 roll").put("status", "ordered").put("orderRef", "Walmart #1").put("source", "ai").put("addedBy", "Claude").put("addedAt", "2026-10-20T10:00:00Z")))
            .put("pantry", JSONArray().put(JSONObject().put("id", "p1").put("name", "Rice").put("section", "Grains").put("status", "low").put("note", "Brown")))
            .put(
                "week",
                JSONObject()
                    .put("week", JSONObject().put("start", start.toString()).put("end", start.plusDays(6).toString()).put("title", "Meal Plan"))
                    .put("status", "ordered").put("tagline", "For 2").put("meta", JSONArray().put("Ordered · **Mon**"))
                    .put("days", JSONArray().put(JSONObject().put("date", start.toString()).put("label", "Tue 10/27").put("dinner", "Salmon").put("base", "rice").put("note", "Thaw").put("kind", "cook").put("recipe", "r1")).put(JSONObject().put("date", start.plusDays(6).toString()).put("dinner", "Leftovers").put("kind", "leftovers")))
                    .put("boxes", JSONArray().put(JSONObject().put("title", "Lunch").put("items", JSONArray().put("Wraps"))))
                    .put("recipes", JSONArray().put(JSONObject().put("id", "r1").put("title", "Salmon").put("sub", "45 min").put("ingredients", JSONArray().put("1 lb salmon").put("1 lemon")).put("steps", JSONArray().put("Bake")).put("note", "Good"))),
            )
            .put("nextWeek", JSONObject.NULL)
            .put("notes", JSONObject().put("2026-10-27", JSONObject().put("cooked", "yes").put("rating", "ok").put("note", "Fine").put("updatedAt", "2026-10-28T01:00:00Z")))
        val snapshot = KitchenParser.parseSnapshot(json)
        assertEquals(snapshot, KitchenParser.parseSnapshot(JSONObject(KitchenCacheJson.write(snapshot).toString())))
    }
}
