package com.example.aicalendar

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * HARNESS ONLY: a fictional household's kitchen for the Kitchen screens and flows. The meal-plan
 * week runs Tuesday to Monday around [today], as Claude's Monday order publishes it.
 */
internal object KitchenSample {
    const val ORDER = "Walmart #200000000001, Mon 11/2"

    fun weekStart(today: LocalDate = LocalDate.now()): LocalDate = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.TUESDAY))

    fun server(today: LocalDate = LocalDate.now(), now: Instant = Instant.now()): FakeKitchenServer {
        val server = FakeKitchenServer()
        server.now = { now }
        val start = weekStart(today)
        server.week = week(start)
        // How the dinners before today went.
        (0 until 7).map { start.plusDays(it.toLong()) }.filter { it.isBefore(today) }.forEachIndexed { index, date ->
            val note = when (index % 3) {
                0 -> JSONObject().put("cooked", "yes").put("rating", "loved").put("note", "Make again")
                1 -> JSONObject().put("cooked", "yes").put("rating", "ok").put("note", "")
                else -> JSONObject().put("cooked", "skipped").put("rating", "").put("note", "Ate out")
            }
            server.notes[date.toString()] = note.put("updatedAt", now.toString())
        }

        fun ago(hours: Long) = now.minus(Duration.ofHours(hours))
        server.listItem("Aluminum foil", qty = "1 roll", addedAt = ago(80))
        server.listItem("Dish soap", addedAt = ago(50))
        server.listItem("Eggs", qty = "1 dozen", source = "pantry", addedBy = "Pantry", addedAt = ago(20))
        server.listItem("Coffee filters", note = "#4 cone", source = "ai", addedBy = "Claude", addedAt = ago(5))
        server.listItem("Salmon fillets", status = "ordered", qty = "1 lb", orderRef = ORDER, source = "ai", addedBy = "Claude", addedAt = ago(30))
        server.listItem("Brown rice", status = "ordered", qty = "2 lb bag", orderRef = ORDER, source = "ai", addedBy = "Claude", addedAt = ago(29))
        server.listItem("Bananas", status = "got", addedAt = ago(100), doneAt = ago(26))
        server.listItem("Milk", status = "got", qty = "1 gal", addedAt = ago(120), doneAt = ago(70))
        // Older than 7 days: the snapshot leaves it out.
        server.listItem("Paper plates", status = "got", addedAt = ago(400), doneAt = ago(240))

        // Claude's estimate, last checked last night, with why it thinks so.
        val checked = ago(20)
        listOf(
            listOf("Black beans", "Canned & jarred", "have", "2 cans, bought 10/26"),
            listOf("Diced tomatoes", "Canned & jarred", "low", "1 can left after Wed jambalaya"),
            listOf("Chicken broth", "Canned & jarred", "have", "carton opened Sun"),
            listOf("Chicken thighs", "Freezer", "have", "bought 10/26, 2 lb"),
            listOf("Frozen corn", "Freezer", "have", "bag in the freezer since 10/12"),
            listOf("Turkey burger patties", "Freezer", "low", "4 used in Thu burgers"),
            listOf("Eggs", "Fridge & counter", "out", "Pixel 10 Pro XL said it's out"),
            listOf("Butter", "Fridge & counter", "have", "bought 10/19"),
            listOf("Onions", "Fridge & counter", "low", "2 left after the chili"),
            listOf("BBQ sauce", "Sauces & condiments", "have", "half a bottle"),
            listOf("Olive oil", "Sauces & condiments", "have", "bought 10/12"),
            listOf("Smoked paprika", "Sauces & condiments", "low", "nearly empty jar"),
        ).forEach { (name, section, status, basis) -> server.pantryItem(name, section, status, basis = basis, checkedAt = checked) }
        server.pantry.first { it.getString("name") == "Chicken broth" }.put("note", "Low sodium")

        // People's corrections: one waiting for Claude, three handled.
        val eggs = server.pantry.first { it.getString("name") == "Eggs" }
        server.correction("note", "Is there any rice left?", status = "dismissed", reply = "Yes: about half a bag of brown rice, from the 10/19 order.", addedBy = "Galaxy S24", addedAt = ago(96), resolvedAt = ago(72))
        server.correction("note", "Tossed the cilantro, it went bad", status = "applied", reply = "Removed cilantro from the pantry.", addedBy = "Galaxy S24", addedAt = ago(48), resolvedAt = ago(24))
        server.correction("out", item = eggs, status = "applied", reply = "Marked out and added to the shopping list.", addedAt = ago(20), resolvedAt = ago(20))
        server.correction("note", "We have 2 cans of chickpeas in the garage", addedBy = "Galaxy S24", addedAt = ago(3))
        return server
    }

    private val label = DateTimeFormatter.ofPattern("EEE M/d")
    private val title = DateTimeFormatter.ofPattern("MMM d")

    /** Tuesday to Monday: salmon, jambalaya, burgers, chili, BBQ chicken, black bean burgers, leftovers. */
    fun week(start: LocalDate): JSONObject {
        data class Dinner(val dinner: String, val base: String, val recipe: String?, val note: String = "", val kind: String = "cook")
        val dinners = listOf(
            Dinner("Oven-baked salmon", "sweet potato", "salmon", note = "Thaw 1 lb chicken tonight"),
            Dinner("Chicken jambalaya", "brown rice", "jambalaya"),
            Dinner("Turkey burgers", "oven fries", "burgers"),
            Dinner("Turkey & bean chili", "cornbread", "chili", note = "Make a double batch for Monday"),
            Dinner("BBQ chicken", "corn on the cob", "bbq"),
            Dinner("Black bean burgers", "side salad", "blackbean"),
            Dinner("Leftover chili", "", null, note = "Groceries arrive **4–6pm**", kind = "leftovers"),
        )
        val days = JSONArray()
        dinners.forEachIndexed { index, dinner ->
            val date = start.plusDays(index.toLong())
            val day = JSONObject()
                .put("date", date.toString())
                .put("label", date.format(label))
                .put("dinner", dinner.dinner)
                .put("base", dinner.base)
                .put("note", dinner.note)
                .put("kind", dinner.kind)
            if (dinner.recipe != null) day.put("recipe", dinner.recipe)
            days.put(day)
        }
        val end = start.plusDays(6)
        return JSONObject()
            .put("week", JSONObject().put("start", start.toString()).put("end", end.toString()).put("title", "Meal Plan · ${start.format(title)}–${end.format(title)}, ${end.year}"))
            .put("status", "ordered")
            .put("tagline", "For 2 · quick weeknights · make-ahead lunches")
            .put("meta", JSONArray().put("Ordered · delivery **Mon 4–6pm** · no substitutions"))
            .put("days", days)
            .put(
                "boxes",
                JSONArray().put(
                    JSONObject().put("title", "Breakfast & lunch").put(
                        "items",
                        JSONArray().put("Oatmeal with berries").put("Turkey & spinach wraps").put("Greek yogurt, bananas"),
                    ),
                ),
            )
            .put(
                "recipes",
                JSONArray()
                    .put(
                        recipe(
                            "salmon", "Oven-baked salmon", "Tue · 75 min · serves 2",
                            "Skin-on salmon (~1 lb), 1 tbsp olive oil, 1 tbsp brown sugar, 1 tsp smoked paprika, ½ tsp garlic powder, **1 lemon**, 2 sweet potatoes",
                            listOf(
                                "Heat the smoker to 225°F.",
                                "Pat the salmon dry. Rub with the oil, sugar and spices.",
                                "Smoke skin side down for 60–75 min, to **140°F** inside.",
                                "Bake the sweet potatoes at 400°F for 45 min alongside.",
                                "Squeeze the lemon over and serve.",
                            ),
                            "Leftover salmon goes into Wednesday's lunch wraps.",
                        ),
                    )
                    .put(
                        recipe(
                            "jambalaya", "Chicken jambalaya", "Wed · 45 min · serves 2",
                            "1 lb chicken thighs, 1 onion, 1 bell pepper, 2 celery stalks, 1 can diced tomatoes, 1 cup brown rice, 2 cups chicken broth, Cajun seasoning",
                            listOf("Brown the chicken in a deep pan.", "Add the onion, pepper and celery; cook 5 min.", "Stir in the rice, tomatoes, broth and seasoning.", "Cover and simmer 35 min."),
                        ),
                    )
                    .put(recipe("burgers", "Turkey burgers", "Thu · 30 min · serves 2", "4 turkey patties, buns, lettuce, tomato, frozen fries", listOf("Bake the fries at 425°F.", "Grill the patties 5 min a side, to 165°F.")))
                    .put(recipe("chili", "Turkey & bean chili", "Fri · 60 min · serves 4", "1 lb ground turkey, 2 cans beans, 1 can diced tomatoes, onion, chili powder, cornbread mix", listOf("Brown the turkey with the onion.", "Add the rest and simmer 45 min.", "Bake the cornbread meanwhile.")))
                    .put(recipe("bbq", "BBQ chicken", "Sat · 50 min · serves 2", "4 chicken thighs, BBQ sauce, 2 ears of corn", listOf("Grill the chicken 35 min over indirect heat.", "Brush with sauce for the last 10 min.", "Grill the corn alongside.")))
                    .put(recipe("blackbean", "Black bean burgers", "Sun · 35 min · serves 2", "1 can black beans, ½ cup oats, 1 egg, cumin, buns, salad greens", listOf("Mash the beans with the oats, egg and cumin.", "Shape 4 patties; pan-fry 4 min a side."))),
            )
    }

    private fun recipe(id: String, title: String, sub: String, ingredients: String, steps: List<String>, note: String = ""): JSONObject =
        JSONObject()
            .put("id", id)
            .put("title", title)
            .put("sub", sub)
            .put("ingredients", ingredients)
            .put("steps", JSONArray(steps))
            .put("note", note)
}
