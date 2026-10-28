// HARNESS ONLY: an in-memory stand-in for the bridge's kitchen routes (docs\kitchen.md),
// so the Kitchen view runs against answers shaped like the real ones, through the real
// KitchenClient and KitchenParser. It applies the server rules the screens depend on: an open
// item is not added twice (rule 1), a pantry item marked out goes on the list (rule 2), and a
// bought item restocks the pantry (rule 3).
package com.example.aicalendar

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

class FakeKitchenServer {
    data class Call(val method: String, val path: String, val body: JSONObject?)

    /** Every request the view made, for the flows to check. */
    val calls = mutableListOf<Call>()

    /** When true every request fails as if the phone had no connection. */
    var offline = false

    /** A message here makes the bridge refuse that request with 400 (method, path, body). */
    var refuse: (String, String, JSONObject?) -> String? = { _, _, _ -> null }

    var revision = 1L
    val list = mutableListOf<JSONObject>()
    val pantry = mutableListOf<JSONObject>()
    var week: JSONObject? = null
    var nextWeek: JSONObject? = null
    val notes = linkedMapOf<String, JSONObject>()
    val corrections = mutableListOf<JSONObject>()

    /** The name this phone is registered under (the bridge's `addedBy`). */
    var deviceName = "Pixel 10 Pro XL"
    var now: () -> Instant = { Instant.now() }
    private var counter = 0

    fun writes(): List<Call> = calls.filter { it.method != "GET" }

    fun handle(method: String, path: String, body: JSONObject?): JSONObject {
        calls += Call(method, path, body?.let { JSONObject(it.toString()) })
        if (offline) throw java.io.IOException("Unable to resolve host")
        refuse(method, path, body)?.let { throw BridgeHttpException(400, it) }
        val parts = path.removePrefix("/v1/kitchen").split('/').filter(String::isNotEmpty)
        return when {
            method == "GET" && parts.isEmpty() -> snapshot()
            method == "POST" && parts == listOf("items") -> addItem(body ?: JSONObject())
            method == "PATCH" && parts.size == 2 && parts[0] == "items" -> patchItem(parts[1], body ?: JSONObject())
            method == "DELETE" && parts.size == 2 && parts[0] == "items" -> {
                if (!list.removeIf { it.getString("id") == parts[1] }) throw BridgeHttpException(404, "Not found")
                JSONObject().put("revision", ++revision).put("deleted", true)
            }
            method == "PATCH" && parts.size == 2 && parts[0] == "pantry" -> patchPantry(parts[1], body ?: JSONObject())
            method == "POST" && parts == listOf("pantry") -> addPantry(body ?: JSONObject())
            method == "DELETE" && parts.size == 2 && parts[0] == "pantry" -> {
                if (!pantry.removeIf { it.getString("id") == parts[1] }) throw BridgeHttpException(404, "Pantry item not found")
                JSONObject().put("revision", ++revision).put("deleted", true)
            }
            method == "POST" && parts == listOf("corrections") -> addCorrection(body ?: JSONObject())
            method == "DELETE" && parts.size == 2 && parts[0] == "corrections" -> {
                if (!corrections.removeIf { it.getString("id") == parts[1] && it.getString("status") == "open" }) {
                    throw BridgeHttpException(404, "No open correction with that id")
                }
                JSONObject().put("revision", ++revision).put("deleted", true)
            }
            method == "PUT" && parts.size == 2 && parts[0] == "notes" -> {
                val note = (notes[parts[1]] ?: JSONObject().put("cooked", "").put("rating", "").put("note", ""))
                listOf("cooked", "rating", "note").forEach { key -> if (body?.has(key) == true) note.put(key, body.getString(key)) }
                note.put("updatedAt", now().toString())
                notes[parts[1]] = note
                JSONObject().put("revision", ++revision).put("date", parts[1]).put("note", JSONObject(note.toString()))
            }
            else -> throw BridgeHttpException(404, "Not found")
        }
    }

    fun snapshot(): JSONObject {
        val got = list.filter { it.getString("status") == "got" }
            .filter { item -> item.optString("doneAt").let { it.isEmpty() || Duration.between(Instant.parse(it), now()) <= Duration.ofDays(7) } }
            .sortedByDescending { it.optString("doneAt") }
        val ordered = list.filter { it.getString("status") == "need" }.sortedBy { it.getString("addedAt") } +
            list.filter { it.getString("status") == "ordered" }.sortedBy { it.getString("addedAt") } + got
        val shelves = pantry.sortedWith(compareBy({ it.getString("section") }, { it.getString("name") }))
        val noteJson = JSONObject().also { out -> notes.forEach { (date, note) -> out.put(date, note) } }
        return JSONObject()
            .put("revision", revision)
            .put("list", JSONArray(ordered))
            .put("pantry", JSONArray(shelves))
            .put("week", week ?: JSONObject.NULL)
            .put("nextWeek", nextWeek ?: JSONObject.NULL)
            .put("notes", noteJson)
            .put("corrections", JSONArray(orderedCorrections()))
    }

    /** Open ones oldest first, then the ones handled in the last 7 days, newest first. */
    private fun orderedCorrections(): List<JSONObject> {
        val open = corrections.filter { it.getString("status") == "open" }.sortedBy { it.getString("addedAt") }
        val handled = corrections.filter { it.getString("status") != "open" }
            .filter { Duration.between(Instant.parse(it.optString("resolvedAt").ifEmpty { it.getString("addedAt") }), now()) <= Duration.ofDays(7) }
            .sortedByDescending { it.optString("resolvedAt") }
        return open + handled
    }

    fun correction(
        kind: String,
        text: String = "",
        item: JSONObject? = null,
        status: String = "open",
        reply: String = "",
        addedBy: String = deviceName,
        addedAt: Instant = now(),
        resolvedAt: Instant? = null,
    ): JSONObject {
        val correction = JSONObject()
            .put("id", newId("c"))
            .put("kind", kind)
            .put("text", text)
            .put("status", status)
            .put("reply", reply)
            .put("addedBy", addedBy)
            .put("addedAt", addedAt.toString())
        if (item != null) correction.put("itemId", item.getString("id")).put("itemName", item.getString("name"))
        if (resolvedAt != null) correction.put("resolvedAt", resolvedAt.toString())
        corrections += correction
        return correction
    }

    fun correctionAbout(text: String): JSONObject? = corrections.firstOrNull { it.optString("text") == text }

    private fun addCorrection(body: JSONObject): JSONObject {
        val kind = body.optString("kind")
        if (kind !in setOf("out", "have", "note")) throw BridgeHttpException(400, "kind must be out, have or note")
        val text = body.optString("text").trim()
        if (kind == "note" && text.isEmpty()) throw BridgeHttpException(400, "A note needs text (1-300 characters)")
        val itemId = body.optString("itemId").ifEmpty { null }
        if (kind != "note" && itemId == null) throw BridgeHttpException(400, "out and have need the pantry item's itemId")
        val item = itemId?.let { id -> pantry.firstOrNull { it.getString("id") == id } ?: throw BridgeHttpException(404, "Pantry item not found") }
        val answer = JSONObject()
        var reply = ""
        if (item != null && kind != "note") {
            item.put("status", kind).put("basis", if (kind == "out") "$deviceName said it's out" else "$deviceName said you have it")
            item.put("updatedAt", now().toString())
            if (kind == "out") {
                val listed = openItem(item.getString("name")) == null
                if (listed) answer.put("listItem", JSONObject(listItem(item.getString("name"), source = "pantry", addedBy = "Pantry", addedAt = now()).toString()))
                reply = if (listed) "Marked out and added to the shopping list." else "Marked out; it was already on the shopping list."
            } else {
                reply = "Marked as on hand."
            }
            answer.put("item", JSONObject(item.toString()))
        }
        val stored = correction(
            kind, text, item,
            status = if (kind == "note") "open" else "applied",
            reply = reply,
            resolvedAt = if (kind == "note") null else now(),
        )
        return answer.put("correction", JSONObject(stored.toString())).put("revision", ++revision)
    }

    fun listItem(
        name: String,
        status: String = "need",
        qty: String = "",
        note: String = "",
        orderRef: String = "",
        source: String = "device",
        addedBy: String = "Pixel 10 Pro XL",
        addedAt: Instant = now().minus(Duration.ofDays(2)),
        doneAt: Instant? = null,
    ): JSONObject {
        val item = JSONObject()
            .put("id", newId("k"))
            .put("name", name)
            .put("qty", qty)
            .put("note", note)
            .put("status", status)
            .put("orderRef", orderRef)
            .put("source", source)
            .put("addedBy", addedBy)
            .put("addedAt", addedAt.toString())
            .put("updatedAt", (doneAt ?: addedAt).toString())
        if (doneAt != null) item.put("doneAt", doneAt.toString())
        list += item
        return item
    }

    fun pantryItem(name: String, section: String, status: String = "have", note: String = "", basis: String = "", checkedAt: Instant? = null): JSONObject {
        val item = JSONObject()
            .put("id", newId("p"))
            .put("name", name)
            .put("section", section)
            .put("status", status)
            .put("note", note)
            .put("basis", basis)
            .put("updatedAt", now().toString())
        if (checkedAt != null) item.put("checkedAt", checkedAt.toString())
        pantry += item
        return item
    }

    fun item(name: String): JSONObject? = list.firstOrNull { it.getString("name") == name }

    private fun addItem(body: JSONObject): JSONObject {
        val name = body.optString("name").trim()
        if (name.isEmpty()) throw BridgeHttpException(400, "name is required")
        val open = openItem(name)
        if (open != null) {
            if (body.has("qty")) open.put("qty", body.getString("qty"))
            if (body.has("note")) open.put("note", body.getString("note"))
            return JSONObject().put("revision", ++revision).put("item", JSONObject(open.toString())).put("created", false)
        }
        val item = listItem(name, qty = body.optString("qty"), note = body.optString("note"), addedAt = now())
        return JSONObject().put("revision", ++revision).put("item", JSONObject(item.toString())).put("created", true)
    }

    private fun patchItem(id: String, body: JSONObject): JSONObject {
        val item = list.firstOrNull { it.getString("id") == id } ?: throw BridgeHttpException(404, "Not found")
        listOf("name", "qty", "note").forEach { key -> if (body.has(key)) item.put(key, body.getString(key)) }
        if (body.has("status")) {
            val status = body.getString("status")
            if (status != "need" && status != "got") throw BridgeHttpException(400, "status must be need or got")
            item.put("status", status)
            if (status == "got") {
                item.put("doneAt", now().toString())
                pantry.filter { same(it.getString("name"), item.getString("name")) }.forEach { it.put("status", "have") }
            } else {
                item.remove("doneAt")
            }
        }
        item.put("updatedAt", now().toString())
        return JSONObject().put("revision", ++revision).put("item", JSONObject(item.toString()))
    }

    private fun patchPantry(id: String, body: JSONObject): JSONObject {
        val item = pantry.firstOrNull { it.getString("id") == id } ?: throw BridgeHttpException(404, "Not found")
        listOf("status", "note", "section").forEach { key -> if (body.has(key)) item.put(key, body.getString(key)) }
        item.put("updatedAt", now().toString())
        val answer = JSONObject().put("item", JSONObject(item.toString()))
        if (item.getString("status") == "out" && openItem(item.getString("name")) == null) {
            val added = listItem(item.getString("name"), source = "pantry", addedBy = "Pantry", addedAt = now())
            answer.put("listItem", JSONObject(added.toString()))
        }
        return answer.put("revision", ++revision)
    }

    private fun addPantry(body: JSONObject): JSONObject {
        val name = body.optString("name").trim()
        if (name.isEmpty()) throw BridgeHttpException(400, "name is required")
        val existing = pantry.firstOrNull { same(it.getString("name"), name) }
        if (existing != null) return JSONObject().put("revision", revision).put("item", JSONObject(existing.toString())).put("created", false)
        val item = pantryItem(name, body.optString("section").ifBlank { "Other" }, body.optString("status").ifBlank { "have" })
        val answer = JSONObject().put("item", JSONObject(item.toString())).put("created", true)
        if (item.getString("status") == "out" && openItem(name) == null) {
            answer.put("listItem", JSONObject(listItem(name, source = "pantry", addedBy = "Pantry", addedAt = now()).toString()))
        }
        return answer.put("revision", ++revision)
    }

    private fun openItem(name: String): JSONObject? =
        list.firstOrNull { same(it.getString("name"), name) && it.getString("status") != "got" }

    private fun same(a: String, b: String): Boolean = key(a) == key(b)

    private fun key(name: String): String = name.lowercase().filter(Char::isLetterOrDigit)

    private fun newId(prefix: String): String = prefix + (++counter).toString(36).padStart(12, '0')
}
