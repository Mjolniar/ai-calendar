package com.example.aicalendar

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Kitchen: the household's shopping list, pantry and weekly meal plan (docs\kitchen.md).
// Every kitchen rule lives on the server; the phone shows GET /v1/kitchen and sends small edits.
// Nothing here touches reminders, alarms or the reminders outbox, and nothing is stored on the phone.

// ------------------------------------------------------------------ records

enum class ListStatus(val wire: String) {
    NEED("need"), ORDERED("ordered"), GOT("got");

    val isOpen: Boolean get() = this != GOT

    companion object {
        fun fromWire(value: String): ListStatus? = entries.firstOrNull { it.wire == value }
    }
}

/** One line of the shopping list. */
data class KitchenListItem(
    val id: String,
    val name: String,
    val qty: String = "",
    val note: String = "",
    val status: ListStatus = ListStatus.NEED,
    val orderRef: String = "",
    /** `device`, `ai` or `pantry`. */
    val source: String = "",
    val addedBy: String = "",
    val addedAt: String = "",
    val updatedAt: String = "",
    val doneAt: String = "",
)

enum class PantryStatus(val wire: String, val label: String) {
    HAVE("have", "Have"), LOW("low", "Low"), OUT("out", "Out");

    companion object {
        fun fromWire(value: String): PantryStatus? = entries.firstOrNull { it.wire == value }
    }
}

/** One line of Claude's estimate of what is on hand. [status] is the estimate; [basis] says why. */
data class PantryItem(
    val id: String,
    val name: String,
    val section: String = PANTRY_OTHER,
    val status: PantryStatus = PantryStatus.HAVE,
    val note: String = "",
    val updatedAt: String = "",
    /** Why Claude thinks so: "bought 10/26", "used in Wed jambalaya", "Pixel said it's out". */
    val basis: String = "",
    /** When Claude last looked at it. */
    val checkedAt: String = "",
)

enum class CorrectionKind(val wire: String) {
    OUT("out"), HAVE("have"), NOTE("note");

    companion object {
        fun fromWire(value: String): CorrectionKind? = entries.firstOrNull { it.wire == value }
    }
}

enum class CorrectionStatus(val wire: String) {
    OPEN("open"), APPLIED("applied"), DISMISSED("dismissed");

    companion object {
        fun fromWire(value: String): CorrectionStatus? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * A person's correction to the estimate. `out` and `have` name an item and apply at once; a
 * `note` waits until Claude answers it ([reply]).
 */
data class Correction(
    val id: String,
    val kind: CorrectionKind,
    val text: String = "",
    val itemId: String = "",
    val itemName: String = "",
    val status: CorrectionStatus = CorrectionStatus.OPEN,
    val reply: String = "",
    val addedBy: String = "",
    val addedAt: String = "",
    val resolvedAt: String = "",
) {
    /** Made on this phone and not sent yet. */
    val queued: Boolean get() = isLocalKitchenId(id)
}

const val PANTRY_OTHER = "Other"

enum class DinnerKind { COOK, LEFTOVERS, SPECIAL }

/** One dinner of a meal-plan week. [recipeId] is blank on leftovers nights. */
data class MealDay(
    val date: LocalDate,
    val label: String,
    val dinner: String,
    val base: String = "",
    val note: String = "",
    val kind: DinnerKind = DinnerKind.COOK,
    val recipeId: String = "",
)

data class Recipe(
    val id: String,
    val title: String,
    val sub: String = "",
    val ingredients: String = "",
    val steps: List<String> = emptyList(),
    val note: String = "",
)

/** A titled list in the plan, such as "Breakfast & lunch". */
data class PlanBox(val title: String, val items: List<String>)

/** One published week, Tuesday to the next Monday. */
data class MealPlan(
    val start: LocalDate?,
    val end: LocalDate?,
    val title: String,
    val status: String = "",
    val tagline: String = "",
    val meta: List<String> = emptyList(),
    val days: List<MealDay> = emptyList(),
    val boxes: List<PlanBox> = emptyList(),
    val recipes: List<Recipe> = emptyList(),
) {
    fun recipeFor(day: MealDay): Recipe? =
        day.recipeId.takeIf(String::isNotBlank)?.let { id -> recipes.firstOrNull { it.id == id } }

    /** True when [date] falls in this week: one of its days, or between its start and end. */
    fun covers(date: LocalDate): Boolean =
        days.any { it.date == date } || (start != null && end != null && !date.isBefore(start) && !date.isAfter(end))
}

enum class Cooked(val wire: String, val label: String) {
    NONE("", ""), YES("yes", "Cooked"), SKIPPED("skipped", "Skipped");

    companion object {
        fun fromWire(value: String): Cooked = entries.firstOrNull { it.wire == value } ?: NONE
    }
}

enum class Rating(val wire: String, val label: String) {
    NONE("", ""), LOVED("loved", "Loved it"), OK("ok", "It was OK"), NO("no", "Not again");

    companion object {
        fun fromWire(value: String): Rating = entries.firstOrNull { it.wire == value } ?: NONE
    }
}

/** How a dinner went, keyed by its date. */
data class DinnerNote(
    val cooked: Cooked = Cooked.NONE,
    val rating: Rating = Rating.NONE,
    val note: String = "",
    val updatedAt: String = "",
) {
    val isEmpty: Boolean get() = cooked == Cooked.NONE && rating == Rating.NONE && note.isBlank()

    /** "Cooked · Loved it", or "" when nothing was said. */
    val summary: String get() = listOf(cooked.label, rating.label).filter(String::isNotBlank).joinToString(" · ")

    fun sameChoices(other: DinnerNote): Boolean = cooked == other.cooked && rating == other.rating && note.trim() == other.note.trim()
}

data class KitchenSnapshot(
    val revision: Long,
    val list: List<KitchenListItem>,
    val pantry: List<PantryItem>,
    val week: MealPlan?,
    val nextWeek: MealPlan?,
    val notes: Map<String, DinnerNote>,
    /** Corrections to the estimate: open ones oldest first, then handled ones (last 7 days) newest first. */
    val corrections: List<Correction> = emptyList(),
) {
    val openItems: List<KitchenListItem> get() = list.filter { it.status.isOpen }

    fun noteFor(date: LocalDate): DinnerNote = notes[date.toString()] ?: DinnerNote()

    fun withListItem(item: KitchenListItem, revision: Long): KitchenSnapshot =
        copy(revision = maxOf(this.revision, revision), list = KitchenOrder.list(list.filterNot { it.id == item.id } + item))

    fun withoutListItem(id: String, revision: Long): KitchenSnapshot =
        copy(revision = maxOf(this.revision, revision), list = list.filterNot { it.id == id })

    fun withPantryItem(item: PantryItem, revision: Long): KitchenSnapshot =
        copy(revision = maxOf(this.revision, revision), pantry = KitchenOrder.pantry(pantry.filterNot { it.id == item.id } + item))

    fun withNote(date: String, note: DinnerNote, revision: Long): KitchenSnapshot =
        copy(revision = maxOf(this.revision, revision), notes = notes + (date to note))

    fun withCorrection(correction: Correction, revision: Long): KitchenSnapshot =
        copy(revision = maxOf(this.revision, revision), corrections = KitchenOrder.corrections(corrections.filterNot { it.id == correction.id } + correction))

    fun withoutCorrection(id: String, revision: Long): KitchenSnapshot =
        copy(revision = maxOf(this.revision, revision), corrections = corrections.filterNot { it.id == id })
}

// Answers of the write routes.
data class ItemAnswer(val revision: Long, val item: KitchenListItem, val created: Boolean)
data class PantryAnswer(val revision: Long, val item: PantryItem, val listItem: KitchenListItem?, val created: Boolean = true)
data class NoteAnswer(val revision: Long, val date: String, val note: DinnerNote)

/** `POST /v1/kitchen/corrections`: the stored correction, and for out/have the item and any list item it made. */
data class CorrectionAnswer(val revision: Long, val correction: Correction, val item: PantryItem?, val listItem: KitchenListItem?)

/** A 2xx answer that is not the kitchen JSON the phone expects. */
class MalformedKitchenException(message: String) : IOException(message)

// ------------------------------------------------------------------ parsing

/**
 * Reads the kitchen answers. Optional fields may be missing; a malformed list item, pantry item,
 * day or recipe is skipped. Only an answer without a list or pantry array is an error, so a stray
 * page from a proxy never reads as an empty kitchen.
 */
object KitchenParser {
    private val idPattern = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val datePattern = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    fun isValidId(id: String): Boolean = idPattern.matches(id)

    fun parseSnapshot(json: JSONObject): KitchenSnapshot {
        val list = json.optJSONArray("list") ?: throw MalformedKitchenException("The kitchen answer has no shopping list")
        val pantry = json.optJSONArray("pantry") ?: throw MalformedKitchenException("The kitchen answer has no pantry")
        return KitchenSnapshot(
            revision = json.optLong("revision", 0L),
            list = objects(list).mapNotNull(::parseListItem),
            pantry = objects(pantry).mapNotNull(::parsePantryItem),
            week = json.optJSONObject("week")?.let(::parsePlan),
            nextWeek = json.optJSONObject("nextWeek")?.let(::parsePlan),
            notes = parseNotes(json.optJSONObject("notes")),
            // Absent before bridge 0.10.0.
            corrections = KitchenOrder.corrections(objects(json.optJSONArray("corrections")).mapNotNull(::parseCorrection)),
        )
    }

    fun parseListItem(json: JSONObject): KitchenListItem? {
        val id = text(json, "id")
        val name = text(json, "name").trim()
        if (!isValidId(id) || name.isEmpty()) return null
        val status = text(json, "status").ifBlank { ListStatus.NEED.wire }.let(ListStatus::fromWire) ?: return null
        return KitchenListItem(
            id = id,
            name = name,
            qty = text(json, "qty").trim(),
            note = text(json, "note").trim(),
            status = status,
            orderRef = text(json, "orderRef").trim(),
            source = text(json, "source"),
            addedBy = text(json, "addedBy"),
            addedAt = text(json, "addedAt"),
            updatedAt = text(json, "updatedAt"),
            doneAt = text(json, "doneAt"),
        )
    }

    fun parsePantryItem(json: JSONObject): PantryItem? {
        val id = text(json, "id")
        val name = text(json, "name").trim()
        if (!isValidId(id) || name.isEmpty()) return null
        val status = text(json, "status").ifBlank { PantryStatus.HAVE.wire }.let(PantryStatus::fromWire) ?: return null
        return PantryItem(
            id = id,
            name = name,
            section = text(json, "section").trim().ifBlank { PANTRY_OTHER },
            status = status,
            note = text(json, "note").trim(),
            updatedAt = text(json, "updatedAt"),
            basis = text(json, "basis").trim(),
            checkedAt = text(json, "checkedAt"),
        )
    }

    fun parseCorrection(json: JSONObject): Correction? {
        val id = text(json, "id")
        if (!isValidId(id)) return null
        val kind = CorrectionKind.fromWire(text(json, "kind")) ?: return null
        val status = text(json, "status").ifBlank { CorrectionStatus.OPEN.wire }.let(CorrectionStatus::fromWire) ?: return null
        return Correction(
            id = id,
            kind = kind,
            text = text(json, "text").trim(),
            itemId = text(json, "itemId"),
            itemName = text(json, "itemName").trim(),
            status = status,
            reply = text(json, "reply").trim(),
            addedBy = text(json, "addedBy"),
            addedAt = text(json, "addedAt"),
            resolvedAt = text(json, "resolvedAt"),
        )
    }

    fun parseCorrectionAnswer(json: JSONObject): CorrectionAnswer {
        val correction = json.optJSONObject("correction")?.let(::parseCorrection) ?: throw MalformedKitchenException("The answer has no correction")
        return CorrectionAnswer(
            json.optLong("revision", 0L),
            correction,
            json.optJSONObject("item")?.let(::parsePantryItem),
            json.optJSONObject("listItem")?.let(::parseListItem),
        )
    }

    fun parsePlan(json: JSONObject): MealPlan {
        val week = json.optJSONObject("week")
        val start = week?.let { date(text(it, "start")) }
        val end = week?.let { date(text(it, "end")) }
        val days = objects(json.optJSONArray("days")).mapNotNull(::parseDay).sortedBy { it.date }
        return MealPlan(
            start = start,
            end = end,
            title = week?.let { text(it, "title").trim() }.orEmpty(),
            status = text(json, "status"),
            tagline = text(json, "tagline").trim(),
            meta = strings(json.opt("meta")),
            days = days,
            boxes = objects(json.optJSONArray("boxes")).mapNotNull { box ->
                val items = strings(box.opt("items"))
                val title = text(box, "title").trim()
                if (title.isEmpty() && items.isEmpty()) null else PlanBox(title, items)
            },
            recipes = objects(json.optJSONArray("recipes")).mapNotNull(::parseRecipe),
        )
    }

    fun parseDay(json: JSONObject): MealDay? {
        val date = date(text(json, "date")) ?: return null
        val kind = when (text(json, "kind").lowercase()) {
            "leftovers" -> DinnerKind.LEFTOVERS
            "special" -> DinnerKind.SPECIAL
            else -> DinnerKind.COOK
        }
        return MealDay(
            date = date,
            label = text(json, "label").trim(),
            dinner = text(json, "dinner").trim(),
            base = text(json, "base").trim(),
            note = text(json, "note").trim(),
            kind = kind,
            recipeId = text(json, "recipe").trim(),
        )
    }

    fun parseRecipe(json: JSONObject): Recipe? {
        val id = text(json, "id").trim()
        if (id.isEmpty()) return null
        return Recipe(
            id = id,
            title = text(json, "title").trim(),
            sub = text(json, "sub").trim(),
            // A string in the skill's plan.json; a list of lines is read too.
            ingredients = strings(json.opt("ingredients")).joinToString("\n"),
            steps = strings(json.opt("steps")),
            note = text(json, "note").trim(),
        )
    }

    fun parseNote(json: JSONObject): DinnerNote = DinnerNote(
        cooked = Cooked.fromWire(text(json, "cooked")),
        rating = Rating.fromWire(text(json, "rating")),
        note = text(json, "note"),
        updatedAt = text(json, "updatedAt"),
    )

    private fun parseNotes(json: JSONObject?): Map<String, DinnerNote> {
        if (json == null) return emptyMap()
        return buildMap {
            json.keys().forEach { key ->
                val value = json.optJSONObject(key)
                if (datePattern.matches(key) && value != null) put(key, parseNote(value))
            }
        }
    }

    fun parseItemAnswer(json: JSONObject): ItemAnswer {
        val item = json.optJSONObject("item")?.let(::parseListItem) ?: throw MalformedKitchenException("The answer has no item")
        return ItemAnswer(json.optLong("revision", 0L), item, json.optBoolean("created", false))
    }

    fun parsePantryAnswer(json: JSONObject): PantryAnswer {
        val item = json.optJSONObject("item")?.let(::parsePantryItem) ?: throw MalformedKitchenException("The answer has no pantry item")
        return PantryAnswer(
            json.optLong("revision", 0L),
            item,
            json.optJSONObject("listItem")?.let(::parseListItem),
            // Only an add answers `created`; false means a pantry item of that name was already there.
            json.optBoolean("created", true),
        )
    }

    fun parseNoteAnswer(json: JSONObject, date: String): NoteAnswer {
        val note = json.optJSONObject("note") ?: throw MalformedKitchenException("The answer has no note")
        return NoteAnswer(json.optLong("revision", 0L), text(json, "date").ifBlank { date }, parseNote(note))
    }

    /** A string field; "" when missing or null (Android's optString would say "null"). Numbers are read as text. */
    private fun text(json: JSONObject, key: String): String = when (val value = json.opt(key)) {
        null, JSONObject.NULL -> ""
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> ""
    }

    /** A list of strings from an array (non-strings skipped) or a single string. */
    private fun strings(value: Any?): List<String> = when (value) {
        is String -> listOf(value.trim()).filter(String::isNotEmpty)
        is JSONArray -> (0 until value.length()).mapNotNull { index ->
            (value.opt(index) as? String)?.trim()?.takeIf(String::isNotEmpty)
        }
        else -> emptyList()
    }

    private fun objects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }

    private fun date(value: String): LocalDate? =
        if (datePattern.matches(value)) runCatching { LocalDate.parse(value) }.getOrNull() else null
}

// ------------------------------------------------------------------ order, days, text

/** The orders the server sends, re-applied when the phone puts a returned item in place. */
object KitchenOrder {
    /** `need` (oldest first), `ordered` (oldest first), then `got` (newest first). */
    fun list(items: List<KitchenListItem>): List<KitchenListItem> {
        val byAdded = compareBy<KitchenListItem>({ instant(it.addedAt) ?: Instant.MAX }, { it.name.lowercase() }, { it.id })
        val need = items.filter { it.status == ListStatus.NEED }.sortedWith(byAdded)
        val ordered = items.filter { it.status == ListStatus.ORDERED }.sortedWith(byAdded)
        val got = items.filter { it.status == ListStatus.GOT }
            .sortedWith(compareByDescending<KitchenListItem> { instant(it.doneAt.ifBlank { it.updatedAt }) ?: Instant.MIN }.thenBy { it.id })
        return need + ordered + got
    }

    /** By section, then name. */
    fun pantry(items: List<PantryItem>): List<PantryItem> =
        items.sortedWith(compareBy({ it.section.lowercase() }, { it.name.lowercase() }, { it.id }))

    /** Sections in the order they come, each with its items. */
    fun pantrySections(items: List<PantryItem>): List<Pair<String, List<PantryItem>>> =
        items.groupBy { it.section }.toList()

    /** The estimate as shown: sections in order, and in each, probably out, then running low, then the rest by name. */
    fun estimateSections(items: List<PantryItem>, attentionOnly: Boolean = false): List<Pair<String, List<PantryItem>>> =
        pantrySections(items).mapNotNull { (section, shelf) ->
            val shown = shelf
                .filter { !attentionOnly || it.status != PantryStatus.HAVE }
                .sortedWith(compareBy({ attentionRank(it.status) }, { it.name.lowercase() }, { it.id }))
            if (shown.isEmpty()) null else section to shown
        }

    private fun attentionRank(status: PantryStatus): Int = when (status) {
        PantryStatus.OUT -> 0
        PantryStatus.LOW -> 1
        PantryStatus.HAVE -> 2
    }

    /** Open corrections oldest first, then handled ones newest first (the bridge's order). */
    fun corrections(items: List<Correction>): List<Correction> {
        val open = items.filter { it.status == CorrectionStatus.OPEN }.sortedWith(compareBy({ instant(it.addedAt) ?: Instant.MAX }, { it.id }))
        val handled = items.filter { it.status != CorrectionStatus.OPEN }
            .sortedWith(compareByDescending<Correction> { instant(it.resolvedAt.ifBlank { it.addedAt }) ?: Instant.MIN }.thenBy { it.id })
        return open + handled
    }

    /** The [count] most recent corrections (by their last change), kept in [corrections]' order. */
    fun newestCorrections(items: List<Correction>, count: Int): List<Correction> {
        val newest = items.sortedByDescending { instant(it.resolvedAt.ifBlank { it.addedAt }) ?: Instant.MAX }.take(count).toSet()
        return items.filter { it in newest }
    }

    /** When Claude last looked at the estimate: the newest `checkedAt`. */
    fun lastChecked(items: List<PantryItem>): Instant? = items.mapNotNull { instant(it.checkedAt) }.maxOrNull()

    /** `got` items from the last 7 days; one without a readable date is kept. */
    fun recentGot(items: List<KitchenListItem>, now: Instant): List<KitchenListItem> = items.filter { item ->
        item.status == ListStatus.GOT && (instant(item.doneAt)?.let { Duration.between(it, now) <= Duration.ofDays(7) } ?: true)
    }

    fun instant(value: String): Instant? = if (value.isBlank()) null else runCatching { Instant.parse(value) }.getOrNull()
}

/** Which week and which day is today, by the phone's own date. */
object KitchenDays {
    fun today(zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): LocalDate = now.atZone(zone).toLocalDate()

    /** The week to show: the one that covers [today], else the current week, else the next one. */
    fun currentPlan(snapshot: KitchenSnapshot, today: LocalDate): MealPlan? =
        listOfNotNull(snapshot.week, snapshot.nextWeek).firstOrNull { it.covers(today) } ?: snapshot.week ?: snapshot.nextWeek

    /** Tonight's dinner, or null when [today] has none in [plan]. */
    fun tonight(plan: MealPlan?, today: LocalDate): MealDay? = plan?.days?.firstOrNull { it.date == today }

    /** The other days of [plan], in date order. */
    fun otherDays(plan: MealPlan, today: LocalDate): List<MealDay> = plan.days.filter { it.date != today }

    /** The next published week, shown after [plan] when [plan] is the current one. */
    fun followingPlan(snapshot: KitchenSnapshot, plan: MealPlan?): MealPlan? =
        snapshot.nextWeek?.takeIf { plan != null && it !== plan }
}

/** `**bold**` in plan strings. */
object KitchenText {
    data class Run(val text: String, val bold: Boolean)

    /** Splits [text] into plain and bold runs. An unpaired `**` stays as written. */
    fun boldRuns(text: String): List<Run> {
        val runs = mutableListOf<Run>()
        fun add(part: String, bold: Boolean) {
            if (part.isEmpty()) return
            val last = runs.lastOrNull()
            if (last != null && last.bold == bold) runs[runs.size - 1] = Run(last.text + part, bold) else runs += Run(part, bold)
        }
        var position = 0
        while (position < text.length) {
            val open = text.indexOf("**", position)
            val close = if (open < 0) -1 else text.indexOf("**", open + 2)
            if (open < 0 || close < 0) {
                add(text.substring(position), false)
                break
            }
            add(text.substring(position, open), false)
            add(text.substring(open + 2, close), true)
            position = close + 2
        }
        return runs
    }

    fun stripBold(text: String): String = boldRuns(text).joinToString("") { it.text }

    /** The server's same-name rule: case, spaces and punctuation do not count. */
    fun sameName(a: String, b: String): Boolean = nameKey(a) == nameKey(b)

    fun nameKey(name: String): String = name.lowercase().filter(Char::isLetterOrDigit)
}

// ------------------------------------------------------------------ the client

const val KITCHEN_OFFLINE = "Can't reach the cloud calendar. Kitchen needs a connection."

sealed interface KitchenResult<out T> {
    data class Ok<T>(val value: T) : KitchenResult<T>

    /**
     * Nothing changed. [connection]: the cloud could not be reached (or this phone is not paired);
     * [notFound]: the item is gone, so the view should be fetched again.
     */
    data class Failed(val message: String, val connection: Boolean = false, val notFound: Boolean = false) : KitchenResult<Nothing>
}

/**
 * The kitchen routes of the device API. [transport] makes one authenticated call to the bridge and
 * throws [BridgeHttpException] for a non-2xx answer (BridgeApi.kitchenClient gives the real one).
 * Every call blocks; run it off the main thread.
 */
class KitchenClient(private val transport: (method: String, path: String, body: JSONObject?) -> JSONObject) {

    fun load(): KitchenResult<KitchenSnapshot> = call("GET", "/v1/kitchen", null, load = true, parse = KitchenParser::parseSnapshot)

    fun addItem(name: String, qty: String = "", note: String = ""): KitchenResult<ItemAnswer> {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return KitchenResult.Failed("Type what to add first.")
        if (clean.length > 120) return KitchenResult.Failed("That name is too long. Keep it under 120 characters.")
        val body = JSONObject().put("name", clean)
        if (qty.isNotBlank()) body.put("qty", qty.trim().take(40))
        if (note.isNotBlank()) body.put("note", note.trim().take(300))
        return call("POST", "/v1/kitchen/items", body, parse = KitchenParser::parseItemAnswer)
    }

    /** Ticks an item as bought ([ListStatus.GOT]) or puts it back on the list ([ListStatus.NEED]). */
    fun setItemStatus(id: String, status: ListStatus): KitchenResult<ItemAnswer> {
        if (status == ListStatus.ORDERED) return KitchenResult.Failed("Only Claude marks items ordered.")
        return withId(id) { call("PATCH", "/v1/kitchen/items/$id", JSONObject().put("status", status.wire), parse = KitchenParser::parseItemAnswer) }
    }

    fun removeItem(id: String): KitchenResult<Long> =
        withId(id) { call("DELETE", "/v1/kitchen/items/$id", null) { it.optLong("revision", 0L) } }

    fun addPantryItem(name: String, section: String = "", status: PantryStatus = PantryStatus.HAVE): KitchenResult<PantryAnswer> {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return KitchenResult.Failed("Type what to add first.")
        if (clean.length > 120) return KitchenResult.Failed("That name is too long. Keep it under 120 characters.")
        val body = JSONObject().put("name", clean).put("status", status.wire)
        if (section.isNotBlank()) body.put("section", section.trim().take(60))
        return call("POST", "/v1/kitchen/pantry", body, parse = KitchenParser::parsePantryAnswer)
    }

    fun setPantryStatus(id: String, status: PantryStatus): KitchenResult<PantryAnswer> =
        withId(id) { call("PATCH", "/v1/kitchen/pantry/$id", JSONObject().put("status", status.wire), parse = KitchenParser::parsePantryAnswer) }

    fun removePantryItem(id: String): KitchenResult<Long> =
        withId(id) { call("DELETE", "/v1/kitchen/pantry/$id", null) { it.optLong("revision", 0L) } }

    /**
     * A correction to the estimate: `out`/`have` for an item (applied at once), or a `note` for
     * Claude, optionally about an item.
     */
    fun addCorrection(kind: CorrectionKind, text: String = "", itemId: String? = null): KitchenResult<CorrectionAnswer> {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (kind == CorrectionKind.NOTE && clean.isEmpty()) return KitchenResult.Failed("Type what to tell Claude.")
        if (clean.length > 300) return KitchenResult.Failed("That is too long. Keep it under 300 characters.")
        val item = itemId?.takeIf(String::isNotBlank)
        if (kind != CorrectionKind.NOTE && item == null) return KitchenResult.Failed("Pick an item first.")
        if (item != null && !KitchenParser.isValidId(item)) return KitchenResult.Failed("This item can't be changed from the phone.")
        val body = JSONObject().put("kind", kind.wire)
        if (clean.isNotEmpty()) body.put("text", clean)
        if (item != null) body.put("itemId", item)
        return call("POST", "/v1/kitchen/corrections", body, parse = KitchenParser::parseCorrectionAnswer)
    }

    /** Takes back a note Claude has not answered yet. */
    fun withdrawCorrection(id: String): KitchenResult<Long> =
        withId(id) { call("DELETE", "/v1/kitchen/corrections/$id", null) { it.optLong("revision", 0L) } }

    fun saveNote(date: LocalDate, note: DinnerNote): KitchenResult<NoteAnswer> {
        val key = date.toString()
        val body = JSONObject()
            .put("cooked", note.cooked.wire)
            .put("rating", note.rating.wire)
            .put("note", note.note.trim().take(300))
        return call("PUT", "/v1/kitchen/notes/$key", body) { KitchenParser.parseNoteAnswer(it, key) }
    }

    private fun <T> withId(id: String, block: () -> KitchenResult<T>): KitchenResult<T> =
        if (KitchenParser.isValidId(id)) block() else KitchenResult.Failed("This item can't be changed from the phone.")

    private fun <T> call(
        method: String,
        path: String,
        body: JSONObject?,
        load: Boolean = false,
        parse: (JSONObject) -> T,
    ): KitchenResult<T> = try {
        KitchenResult.Ok(parse(transport(method, path, body)))
    } catch (error: Exception) {
        failure(error, load)
    }

    companion object {
        /** What the person is told when a kitchen call fails. */
        fun failure(error: Throwable, load: Boolean = false): KitchenResult.Failed = when {
            error is BridgeHttpException && (error.status == 401 || error.status == 403) ->
                KitchenResult.Failed("The cloud calendar rejected this phone's pairing. Reconnect it in Tools > Cloud Connection.", connection = true)
            // A bridge without the kitchen routes answers 404 to GET /v1/kitchen.
            error is BridgeHttpException && error.status == 404 && load ->
                KitchenResult.Failed("The cloud calendar doesn't offer Kitchen yet.", connection = true)
            error is BridgeHttpException && error.status == 404 ->
                KitchenResult.Failed("That item was changed or removed on another device. The kitchen has been refreshed.", notFound = true)
            error is BridgeHttpException && error.status in 400..499 && error.status !in setOf(408, 425, 429) ->
                KitchenResult.Failed(error.message?.takeIf(String::isNotBlank) ?: "The cloud calendar refused this change.")
            error is BridgeHttpException -> KitchenResult.Failed(KITCHEN_OFFLINE, connection = true)
            error is MalformedKitchenException || error is JSONException ->
                KitchenResult.Failed("The cloud calendar sent an answer Kitchen can't read. Try again later.", connection = true)
            // No connection, an unreadable 2xx page (captive portal), or this phone is not paired.
            error is IOException || error is IllegalStateException || error is IllegalArgumentException ->
                KitchenResult.Failed(KITCHEN_OFFLINE, connection = true)
            else -> KitchenResult.Failed(error.message ?: "Something went wrong.")
        }
    }
}
