package com.example.aicalendar

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Round 3: the pantry as Claude's estimate (basis, checkedAt) and people's corrections. */
class KitchenCorrectionsTest {
    private fun shelf(id: String, name: String, status: String = "have", section: String = "Fridge & counter") =
        JSONObject().put("id", id).put("name", name).put("section", section).put("status", status)

    private fun correction(id: String, kind: String, status: String, addedAt: String, resolvedAt: String? = null) = JSONObject()
        .put("id", id).put("kind", kind).put("status", status).put("addedAt", addedAt).put("addedBy", "Pixel 10 Pro XL")
        .also { if (resolvedAt != null) it.put("resolvedAt", resolvedAt) }

    private fun snapshot(pantry: JSONArray, corrections: JSONArray? = null) = JSONObject()
        .put("revision", 5)
        .put("list", JSONArray())
        .put("pantry", pantry)
        .also { if (corrections != null) it.put("corrections", corrections) }

    // ------------------------------------------------------------------ parsing

    @Test
    fun theEstimateCarriesItsBasisAndWhenItWasChecked() {
        val parsed = KitchenParser.parseSnapshot(
            snapshot(
                JSONArray().put(shelf("p000000000001", "Eggs", "out").put("basis", "Pixel said it's out").put("checkedAt", "2026-10-26T15:00:00Z")),
                JSONArray().put(
                    correction("c000000000001", "note", "open", "2026-10-27T15:00:00Z")
                        .put("text", "Have 2 cans of chickpeas").put("itemId", "p000000000001").put("itemName", "Eggs").put("reply", ""),
                ),
            ),
        )
        val eggs = parsed.pantry.single()
        assertEquals("Pixel said it's out", eggs.basis)
        assertEquals("2026-10-26T15:00:00Z", eggs.checkedAt)
        val note = parsed.corrections.single()
        assertEquals(CorrectionKind.NOTE, note.kind)
        assertEquals(CorrectionStatus.OPEN, note.status)
        assertEquals("Have 2 cans of chickpeas", note.text)
        assertEquals("p000000000001", note.itemId)
        assertEquals("Eggs", note.itemName)
        assertEquals("Pixel 10 Pro XL", note.addedBy)
        assertEquals(Instant.parse("2026-10-26T15:00:00Z"), KitchenOrder.lastChecked(parsed.pantry))
    }

    @Test
    fun anOlderBridgeWithoutTheNewFieldsStillReads() {
        val parsed = KitchenParser.parseSnapshot(snapshot(JSONArray().put(shelf("p000000000001", "Rice"))))
        assertEquals("", parsed.pantry.single().basis)
        assertEquals("", parsed.pantry.single().checkedAt)
        assertTrue(parsed.corrections.isEmpty())
        assertNull(KitchenOrder.lastChecked(parsed.pantry))
    }

    @Test
    fun aMalformedCorrectionIsSkipped() {
        val parsed = KitchenParser.parseSnapshot(
            snapshot(
                JSONArray(),
                JSONArray()
                    .put(correction("bad id", "note", "open", "2026-10-27T15:00:00Z"))
                    .put(correction("c000000000002", "teleport", "open", "2026-10-27T15:00:00Z"))
                    .put(correction("c000000000003", "note", "pondering", "2026-10-27T15:00:00Z"))
                    .put("not an object")
                    .put(JSONObject().put("id", "c000000000004").put("kind", "out").put("itemName", "Milk")),
            ),
        )
        val only = parsed.corrections.single()
        assertEquals("c000000000004", only.id)
        assertEquals("a correction without a status is open", CorrectionStatus.OPEN, only.status)
    }

    @Test
    fun correctionsAreOpenOldestFirstThenHandledNewestFirst() {
        val parsed = KitchenParser.parseSnapshot(
            snapshot(
                JSONArray(),
                JSONArray()
                    .put(correction("c000000000001", "out", "applied", "2026-10-24T10:00:00Z", "2026-10-24T10:00:00Z"))
                    .put(correction("c000000000002", "note", "open", "2026-10-27T10:00:00Z"))
                    .put(correction("c000000000003", "note", "dismissed", "2026-10-25T10:00:00Z", "2026-10-26T10:00:00Z"))
                    .put(correction("c000000000004", "note", "open", "2026-10-26T10:00:00Z")),
            ),
        )
        assertEquals(listOf("c000000000004", "c000000000002", "c000000000003", "c000000000001"), parsed.corrections.map { it.id })
        // Collapsed to the three newest, in the same order.
        assertEquals(listOf("c000000000004", "c000000000002", "c000000000003"), KitchenOrder.newestCorrections(parsed.corrections, 3).map { it.id })
    }

    @Test
    fun theAnswerToACorrectionCarriesTheItemAndTheListItem() {
        val answer = KitchenParser.parseCorrectionAnswer(
            JSONObject().put("revision", 9)
                .put("correction", correction("c000000000001", "out", "applied", "2026-10-27T10:00:00Z", "2026-10-27T10:00:00Z").put("reply", "Marked out and added to the shopping list."))
                .put("item", shelf("p000000000001", "Eggs", "out").put("basis", "Pixel said it's out"))
                .put("listItem", JSONObject().put("id", "k000000000009").put("name", "Eggs").put("source", "pantry")),
        )
        assertEquals("Marked out and added to the shopping list.", answer.correction.reply)
        assertEquals(PantryStatus.OUT, answer.item!!.status)
        assertEquals("k000000000009", answer.listItem!!.id)
        val note = KitchenParser.parseCorrectionAnswer(JSONObject().put("correction", correction("c000000000002", "note", "open", "2026-10-27T10:00:00Z")))
        assertNull(note.item)
        assertNull(note.listItem)
    }

    @Test
    fun runningLowAndOutComeFirstAndCanBeShownAlone() {
        val pantry = KitchenParser.parseSnapshot(
            snapshot(
                JSONArray()
                    .put(shelf("p1", "Butter")).put(shelf("p2", "Eggs", "out")).put(shelf("p3", "Onions", "low"))
                    .put(shelf("p4", "Black beans", section = "Canned & jarred")),
            ),
        ).pantry
        val sections = KitchenOrder.estimateSections(pantry)
        assertEquals(listOf("Fridge & counter", "Canned & jarred"), sections.map { it.first })
        assertEquals(listOf("Eggs", "Onions", "Butter"), sections.first().second.map { it.name })
        val attention = KitchenOrder.estimateSections(pantry, attentionOnly = true)
        assertEquals(listOf("Fridge & counter"), attention.map { it.first })
        assertEquals(listOf("Eggs", "Onions"), attention.single().second.map { it.name })
    }

    // ------------------------------------------------------------------ the client

    @Test
    fun correctionsGoToTheirRoutes() {
        val calls = mutableListOf<Triple<String, String, JSONObject?>>()
        val client = KitchenClient { method, path, body ->
            calls += Triple(method, path, body)
            if (method == "POST") {
                JSONObject().put("revision", 2).put("correction", correction("c000000000001", body!!.getString("kind"), "open", "2026-10-27T10:00:00Z"))
            } else {
                JSONObject().put("revision", 3).put("deleted", true)
            }
        }
        assertTrue(client.addCorrection(CorrectionKind.OUT, itemId = "p000000000001") is KitchenResult.Ok)
        assertTrue(client.addCorrection(CorrectionKind.NOTE, "  Tossed   the cilantro ") is KitchenResult.Ok)
        assertEquals(KitchenResult.Ok(3L), client.withdrawCorrection("c000000000001"))
        assertEquals(listOf("POST /v1/kitchen/corrections", "POST /v1/kitchen/corrections", "DELETE /v1/kitchen/corrections/c000000000001"), calls.map { "${it.first} ${it.second}" })
        assertEquals("out", calls[0].third!!.getString("kind"))
        assertEquals("p000000000001", calls[0].third!!.getString("itemId"))
        assertTrue("an out has no text", !calls[0].third!!.has("text"))
        assertEquals("Tossed the cilantro", calls[1].third!!.getString("text"))
        assertTrue(!calls[1].third!!.has("itemId"))
        // Refused here, without a call: an empty note, an out without its item, too long.
        assertTrue(client.addCorrection(CorrectionKind.NOTE, "   ") is KitchenResult.Failed)
        assertTrue(client.addCorrection(CorrectionKind.OUT) is KitchenResult.Failed)
        assertTrue(client.addCorrection(CorrectionKind.NOTE, "x".repeat(301)) is KitchenResult.Failed)
        assertEquals(3, calls.size)
    }

    // ------------------------------------------------------------------ queued notes

    private val storage = InMemoryOutboxStorage()
    private val outbox = KitchenOutbox(storage)
    private val base = KitchenParser.parseSnapshot(snapshot(JSONArray().put(shelf("p000000000001", "Eggs").put("basis", "bought 10/26"))))

    private fun note(text: String, itemId: String? = null) = outbox.enqueue(
        KitchenOp.CORRECTION,
        newLocalKitchenId(),
        mapOf("kind" to "note", "text" to text) + (itemId?.let { mapOf("itemId" to it) } ?: emptyMap()),
        "Note to Claude: \"$text\"",
        "2026-10-27T18:00:00Z",
    )!!

    @Test
    fun aQueuedNoteShowsAsOpenAndNotSent() {
        val queued = note("Tossed the cilantro")
        val shown = KitchenOverlay.apply(base, outbox.all())
        val open = shown.corrections.single()
        assertEquals(queued.target, open.id)
        assertEquals(CorrectionStatus.OPEN, open.status)
        assertTrue(open.queued)
        assertEquals("Tossed the cilantro", open.text)
        assertEquals("a note leaves the estimate alone", base.pantry, shown.pantry)
        // It survives a restart.
        assertEquals(outbox.all(), KitchenOutbox(InMemoryOutboxStorage(storage.text)).all())
    }

    @Test
    fun aQueuedOutChangesTheEstimateAtOnce() {
        outbox.enqueue(KitchenOp.CORRECTION, newLocalKitchenId(), mapOf("kind" to "out", "itemId" to "p000000000001", "itemName" to "Eggs"), "We're out: Eggs", "2026-10-27T18:00:00Z")
        val shown = KitchenOverlay.apply(base, outbox.all())
        assertEquals(PantryStatus.OUT, shown.pantry.single().status)
        assertTrue(shown.pantry.single().basis.contains("not sent yet"))
        assertEquals("Eggs", shown.corrections.single().itemName)
    }

    @Test
    fun withdrawingANoteThatNeverLeftThePhoneDropsIt() {
        val queued = note("Have 2 cans of beans")
        assertNull(outbox.enqueue(KitchenOp.WITHDRAW_CORRECTION, queued.target))
        assertTrue(outbox.isEmpty())
        assertTrue(KitchenOverlay.apply(base, outbox.all()).corrections.isEmpty())
    }

    @Test
    fun queuedNotesAreSentInOrderAndAWithdrawalFollowsTheRealId() {
        val first = note("Tossed the cilantro")
        note("Eggs: only 2 left", itemId = "p000000000001")
        outbox.enqueue(KitchenOp.WITHDRAW_CORRECTION, first.target)
        val calls = mutableListOf<String>()
        var next = 0
        val client = KitchenClient { method, path, body ->
            calls += "$method $path ${body?.optString("text").orEmpty()}${body?.optString("itemId")?.let { " @$it" }.orEmpty()}".trim()
            if (method == "POST") {
                next++
                JSONObject().put("revision", 2).put("correction", correction("c00000000000$next", "note", "open", "2026-10-27T18:00:00Z"))
            } else {
                JSONObject().put("revision", 3).put("deleted", true)
            }
        }
        // The withdrawal of a queued note drops it instead; so only the second goes, then nothing to withdraw.
        assertEquals(1, outbox.size())
        val report = KitchenFlusher.flush(outbox, client)
        assertEquals(listOf("POST /v1/kitchen/corrections Eggs: only 2 left @p000000000001"), calls)
        assertEquals(1, report.sent)
        assertTrue(outbox.isEmpty())
        // Withdrawn after it was sent: the cloud id is used.
        val sentLater = note("Out of coffee")
        val withdrawStorage = InMemoryOutboxStorage()
        val box = KitchenOutbox(withdrawStorage)
        val added = box.enqueue(KitchenOp.CORRECTION, sentLater.target, sentLater.fields, sentLater.label, sentLater.createdAt)!!
        box.sent(added, "c0000000000ab")
        assertEquals("c0000000000ab", box.enqueue(KitchenOp.WITHDRAW_CORRECTION, sentLater.target)!!.target)
        calls.clear()
        KitchenFlusher.flush(box, client)
        assertEquals(listOf("DELETE /v1/kitchen/corrections/c0000000000ab"), calls)
    }

    @Test
    fun everyCorrectionCarriesTheSameClientIdEachTimeItIsSent() {
        val bodies = mutableListOf<JSONObject>()
        val client = KitchenClient { _, _, body ->
            bodies += body!!
            JSONObject().put("revision", 2).put("correction", correction("c000000000001", body.getString("kind"), "open", "2026-10-27T10:00:00Z"))
        }
        // Straight from the view.
        client.addCorrection(CorrectionKind.OUT, itemId = "p000000000001", clientId = "3f9c2a10-5b7e-4c1d-9a2e-0d6b8e4f7a31")
        assertEquals("3f9c2a10-5b7e-4c1d-9a2e-0d6b8e4f7a31", bodies.last().getString("clientId"))
        // An id the bridge would refuse is left out rather than sent.
        client.addCorrection(CorrectionKind.NOTE, "x", clientId = "not valid!")
        assertTrue(!bodies.last().has("clientId"))
        // Queued: the field the view stored, on every try.
        val local = newLocalKitchenId()
        val clientId = local.removePrefix(LOCAL_ID_PREFIX)
        outbox.enqueue(KitchenOp.CORRECTION, local, mapOf("kind" to "note", "text" to "Out of coffee", "clientId" to clientId), "Note", "2026-10-27T18:00:00Z")
        val failing = KitchenClient { _, _, body ->
            bodies += body!!
            throw java.io.IOException("timeout")
        }
        KitchenFlusher.flush(outbox, failing)
        KitchenFlusher.flush(outbox, client)
        assertEquals(listOf(clientId, clientId), bodies.takeLast(2).map { it.getString("clientId") })
        // One queued by 0.19.0 has no field: its local id's uuid stands in, the same every time.
        val olderLocal = newLocalKitchenId()
        val older = outbox.enqueue(KitchenOp.CORRECTION, olderLocal, mapOf("kind" to "note", "text" to "Old"), "Note", "2026-10-27T18:00:00Z")!!
        assertEquals(olderLocal.removePrefix(LOCAL_ID_PREFIX), KitchenFlusher.clientId(older))
        assertEquals(KitchenFlusher.clientId(older), KitchenFlusher.clientId(KitchenOutbox(InMemoryOutboxStorage(storage.text)).all().single()))
    }

    @Test
    fun aRepeatedCorrectionAndALineTakenOffTheListAreRead() {
        val answer = KitchenParser.parseCorrectionAnswer(
            JSONObject().put("revision", 12).put("repeated", true).put("removedListItemId", "k000000000009")
                .put("correction", correction("c000000000001", "have", "applied", "2026-10-27T10:00:00Z", "2026-10-27T10:00:00Z").put("reply", "Marked as on hand and taken off the shopping list."))
                .put("item", shelf("p000000000001", "Eggs").put("basis", "Pixel said you have it")),
        )
        assertTrue(answer.repeated)
        assertEquals("k000000000009", answer.removedListItemId)
        // Bridge 0.10.0 sends neither.
        val older = KitchenParser.parseCorrectionAnswer(JSONObject().put("correction", correction("c000000000002", "have", "applied", "2026-10-27T10:00:00Z")))
        assertTrue(!older.repeated)
        assertEquals("", older.removedListItemId)
        // The line goes from the copy the phone shows, with the estimate's change.
        val shown = KitchenSnapshot(
            revision = 11,
            list = listOf(KitchenListItem("k000000000009", "Eggs", source = "pantry"), KitchenListItem("k000000000010", "Foil")),
            pantry = listOf(PantryItem("p000000000001", "Eggs", status = PantryStatus.OUT)),
            week = null, nextWeek = null, notes = emptyMap(),
        )
        val after = KitchenAnswer.Corrected(answer).applyTo(shown)
        assertEquals(listOf("k000000000010"), after.list.map { it.id })
        assertEquals(PantryStatus.HAVE, after.pantry.single().status)
        assertEquals(12L, after.revision)
        assertEquals("c000000000001", after.corrections.single().id)
    }

    @Test
    fun aQueuedNoteAboutAnItemRemovedSinceIsSentWithoutIt() {
        note("Eggs: only 2 left", itemId = "p000000000001")
        val bodies = mutableListOf<JSONObject>()
        // Bridge 0.10.0: the item is gone, 404.
        val client = KitchenClient { _, _, body ->
            bodies += body!!
            if (body.has("itemId")) throw BridgeHttpException(404, "Pantry item not found")
            JSONObject().put("revision", 3).put("correction", correction("c000000000005", "note", "open", "2026-10-27T18:00:00Z").put("text", body.getString("text")))
        }
        val report = KitchenFlusher.flush(outbox, client)
        assertEquals(listOf(true, false), bodies.map { it.has("itemId") })
        assertEquals("Eggs: only 2 left", bodies.last().getString("text"))
        assertEquals("the same clientId both times", bodies[0].getString("clientId"), bodies[1].getString("clientId"))
        assertEquals(1, report.sent)
        assertEquals(0, report.gone)
        assertTrue(outbox.isEmpty())
    }

    @Test
    fun aQueuedNoteTheCloudCannotStoreIsNamedNotDropped() {
        note("Tossed the cilantro", itemId = "p000000000001")
        note("Out of coffee")
        // An older bridge without the route answers 404 to everything.
        val report = KitchenFlusher.flush(outbox, KitchenClient { _, _, _ -> throw BridgeHttpException(404, "Not found") })
        assertEquals(0, report.gone)
        assertEquals(
            listOf(
                "Note to Claude: \"Tossed the cilantro\": The cloud calendar couldn't store this note.",
                "Note to Claude: \"Out of coffee\": The cloud calendar couldn't store this note.",
            ),
            report.rejected,
        )
        assertTrue(outbox.isEmpty())
        // We're out for an item removed since is still dropped quietly (the item is gone).
        outbox.enqueue(KitchenOp.CORRECTION, newLocalKitchenId(), mapOf("kind" to "out", "itemId" to "p000000000001"), "We're out: Eggs", "2026-10-27T18:00:00Z")
        val gone = KitchenFlusher.flush(outbox, KitchenClient { _, _, _ -> throw BridgeHttpException(404, "Pantry item not found") })
        assertEquals(1, gone.gone)
        assertTrue(gone.rejected.isEmpty())
    }

    @Test
    fun aRefusedNoteIsDroppedWithItsName() {
        note("x")
        val client = KitchenClient { _, _, _ -> throw BridgeHttpException(400, "At most 50 open corrections") }
        val report = KitchenFlusher.flush(outbox, client)
        assertEquals(listOf("Note to Claude: \"x\": At most 50 open corrections"), report.rejected)
        assertTrue(outbox.isEmpty())
    }

    @Test
    fun theCacheKeepsTheEstimateAndTheCorrections() {
        val parsed = KitchenParser.parseSnapshot(
            snapshot(
                JSONArray().put(shelf("p000000000001", "Eggs", "low").put("basis", "used 6 in Sun pancakes").put("checkedAt", "2026-10-27T03:00:00Z")),
                JSONArray().put(
                    correction("c000000000001", "note", "applied", "2026-10-26T10:00:00Z", "2026-10-27T03:00:00Z")
                        .put("text", "Tossed the cilantro").put("reply", "Removed cilantro.").put("itemName", ""),
                ),
            ),
        )
        assertEquals(parsed, KitchenParser.parseSnapshot(JSONObject(KitchenCacheJson.write(parsed).toString())))
    }
}
