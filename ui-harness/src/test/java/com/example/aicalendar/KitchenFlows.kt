package com.example.aicalendar

import android.app.AlarmManager
import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.example.aicalendar.xp.LocalXpInlineDialogs
import com.example.aicalendar.xp.XpTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowNetwork
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/** HARNESS ONLY: the Kitchen view against a stand-in bridge: what it sends, what it shows, what it leaves alone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w443dp-h986dp-420dpi")
class KitchenFlows {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var context: Context
    private lateinit var server: FakeKitchenServer
    private var resumeTick by mutableIntStateOf(1)
    private val prefs get() = context.getSharedPreferences("planner_view", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Denver"))
        context = RuntimeEnvironment.getApplication()
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        server = KitchenSample.server()
        BridgeApi.kitchen = server
        BridgeApi.doneCalls.clear()
        BridgeApi.seriesCalls.clear()
        BridgeSyncWorker.kitchenJobs = 0
    }

    private fun at(days: Long, hour: Int) =
        LocalDate.now().plusDays(days).atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val reminders by lazy {
        listOf(
            PlannerEvent(id = "rent", title = "Rent due", startAtMillis = at(4, 9), category = EventCategory.FINANCIAL, remote = true, source = "chatgpt"),
            PlannerEvent(id = "shop", title = "Buy groceries", startAtMillis = at(1, 17), source = "manual", reminderOffsetsMinutes = listOf(60)),
        )
    }

    private fun launch(tab: KitchenTab?, connected: Boolean = true) {
        EventRepository(context).saveEvents(reminders)
        if (connected) {
            BridgeSettings(context).saveConfig(BridgeConfig("https://example.invalid/api", "p", "a", "k", "s"))
            BridgeSettings(context).saveDeviceToken("token")
        }
        if (tab != null) prefs.edit().putBoolean("kitchen", true).putString("kitchen_tab", tab.name).commit()
        rule.setContent {
            XpTheme {
                CompositionLocalProvider(LocalXpInlineDialogs provides true) {
                    PlannerApp(resumeTick = resumeTick, openEventId = null, onOpenEventHandled = {}, onMinimize = {}, onExit = {})
                }
            }
        }
        rule.waitForIdle()
    }

    // Kitchen answers arrive from a background thread; idling the UI on each check lets them land.
    private fun waitForText(text: String, substring: Boolean = false) =
        rule.waitUntil(5_000) {
            rule.waitForIdle()
            rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }

    private fun waitForDescription(description: String) =
        rule.waitUntil(5_000) {
            rule.waitForIdle()
            rule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }

    private fun gets() = server.calls.count { it.method == "GET" }

    @Test
    fun theKitchenButtonOpensKitchenAndLeadsBack() {
        launch(tab = null)
        assertTrue("nothing is fetched before Kitchen is opened", server.calls.isEmpty())
        rule.onNodeWithText("Kitchen").performClick()
        waitForText("Tonight")
        rule.onNodeWithText("Kitchen - AI Planner").assertIsDisplayed()
        assertTrue(prefs.getBoolean("kitchen", false))
        assertEquals(1, gets())
        rule.onNodeWithText("Reminders").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Rent due").assertIsDisplayed()
        rule.onNodeWithText("AI Planner").assertIsDisplayed()
        assertFalse(prefs.getBoolean("kitchen", true))
    }

    @Test
    fun addingAnItemSendsItToTheCloudAndListsIt() {
        launch(KitchenTab.LIST)
        waitForText("Aluminum foil")
        rule.onNodeWithText("Shopping list (6)").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).performTextInput("Paper towels")
        rule.onNodeWithText("Add").performClick()
        waitForDescription("Tick off Paper towels")
        val call = server.writes().single()
        assertEquals("POST", call.method)
        assertEquals("/v1/kitchen/items", call.path)
        assertEquals("Paper towels", call.body!!.getString("name"))
        // Listed once (the box is empty again), and counted.
        rule.waitUntil(5_000) { rule.waitForIdle(); rule.onAllNodesWithText("Paper towels").fetchSemanticsNodes().size == 1 }
        rule.onNodeWithText("Shopping list (7)").assertIsDisplayed()
        assertEquals("need", server.item("Paper towels")!!.getString("status"))
    }

    @Test
    fun addingWhatIsAlreadyOnTheListDoesNotDuplicateIt() {
        launch(KitchenTab.LIST)
        waitForText("Dish soap")
        rule.onNode(hasSetTextAction()).performTextInput("dish  soap")
        rule.onNodeWithText("Add").performClick()
        waitForText("Already on the list")
        assertEquals(1, server.list.count { it.getString("name").equals("Dish soap", ignoreCase = true) })
        rule.onNodeWithText("Shopping list (6)").assertIsDisplayed()
    }

    @Test
    fun tickingAnItemMarksItGotAndUntickingPutsItBack() {
        launch(KitchenTab.LIST)
        waitForDescription("Tick off Aluminum foil")
        val id = server.item("Aluminum foil")!!.getString("id")
        rule.onNodeWithContentDescription("Tick off Aluminum foil").performClick()
        waitForDescription("Put Aluminum foil back on the list")
        val tick = server.writes().single()
        assertEquals("PATCH", tick.method)
        assertEquals("/v1/kitchen/items/$id", tick.path)
        assertEquals("got", tick.body!!.getString("status"))
        assertEquals("got", server.item("Aluminum foil")!!.getString("status"))
        rule.onNodeWithText("Shopping list (5)").assertIsDisplayed()
        rule.onNodeWithText("Got it").assertIsDisplayed()

        rule.onNodeWithContentDescription("Put Aluminum foil back on the list").performClick()
        waitForDescription("Tick off Aluminum foil")
        assertEquals("need", server.writes().last().body!!.getString("status"))
        assertEquals("need", server.item("Aluminum foil")!!.getString("status"))
    }

    @Test
    fun buyingSomethingRestocksThePantryView() {
        launch(KitchenTab.LIST)
        waitForDescription("Tick off Eggs")
        rule.onNodeWithContentDescription("Tick off Eggs").performClick()
        waitForDescription("Put Eggs back on the list")
        // The server set the pantry's Eggs to have (rule 3); the view fetched it again.
        rule.waitUntil(5_000) { rule.waitForIdle(); gets() >= 2 }
        rule.onNodeWithText("On hand").performClick()
        showShelf("Eggs")
        waitForDescription("Eggs: Have")
    }

    @Test
    fun removingAnItemCanBeUndone() {
        launch(KitchenTab.LIST)
        waitForDescription("Remove Dish soap")
        rule.onNodeWithContentDescription("Remove Dish soap").performClick()
        waitForText("Removed from the list")
        assertEquals("DELETE", server.writes().single().method)
        rule.waitUntil(5_000) { rule.waitForIdle(); rule.onAllNodesWithContentDescription("Tick off Dish soap").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("Undo").performClick()
        waitForDescription("Tick off Dish soap")
        assertEquals("POST", server.writes().last().method)
    }

    @Test
    fun openingARecipeAndChoosingLovedItSavesHowItWent() {
        server.notes.clear()
        launch(KitchenTab.WEEK)
        waitForText("Chicken jambalaya")
        rule.onNodeWithText("Chicken jambalaya").performClick()
        waitForText("How did it go?")
        rule.onNodeWithText("Ingredients").assertIsDisplayed()
        rule.onNodeWithText("Cover and simmer 35 min.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Loved it").performScrollTo().performClick()
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(5_000) { rule.waitForIdle(); rule.onAllNodesWithText("How did it go?").fetchSemanticsNodes().isEmpty() }
        val call = server.writes().single()
        val date = KitchenSample.weekStart().plusDays(1)
        assertEquals("PUT", call.method)
        assertEquals("/v1/kitchen/notes/$date", call.path)
        assertEquals("loved", call.body!!.getString("rating"))
        assertEquals("", call.body!!.getString("cooked"))
        // The day now says so, in the list or on tonight's card.
        waitForText("Loved it")
        assertEquals("loved", server.notes.getValue(date.toString()).getString("rating"))
    }

    private val outbox get() = KitchenStore(context).outbox

    @Test
    fun offlineEditsAreQueuedShownAndSentWhenBack() {
        launch(KitchenTab.LIST)
        waitForDescription("Tick off Aluminum foil")
        val foil = server.item("Aluminum foil")!!.getString("id")
        server.offline = true
        rule.onNodeWithContentDescription("Tick off Aluminum foil").performClick()
        // Shown at once, kept on the phone, and the bar says so.
        waitForDescription("Put Aluminum foil back on the list")
        waitForText("1 change will be sent when you're back online.", substring = true)
        rule.onNodeWithText("Offline: showing", substring = true).assertIsDisplayed()
        rule.onNode(hasSetTextAction()).performTextInput("Lemons")
        rule.onNodeWithText("Add").performClick()
        waitForDescription("Tick off Lemons")
        waitForText("2 changes will be sent when you're back online.", substring = true)
        assertEquals(listOf(KitchenOp.ITEM_STATUS, KitchenOp.ADD_ITEM), outbox.all().map { it.op })
        assertTrue(isLocalKitchenId(outbox.all().last().target))
        assertEquals("need", server.item("Aluminum foil")!!.getString("status"))
        // A third, on the item that only exists on this phone: it waits behind its add.
        rule.onNodeWithContentDescription("Tick off Lemons").performClick()
        waitForDescription("Put Lemons back on the list")
        assertEquals(3, outbox.size())

        server.offline = false
        rule.onNodeWithContentDescription("Refresh kitchen").performClick()
        rule.waitUntil(5_000) { rule.waitForIdle(); outbox.isEmpty() }
        rule.waitUntil(5_000) { rule.waitForIdle(); rule.onAllNodesWithText("Offline: showing", substring = true).fetchSemanticsNodes().isEmpty() }
        waitForDescription("Put Lemons back on the list")
        assertEquals("got", server.item("Aluminum foil")!!.getString("status"))
        val lemons = server.item("Lemons")!!
        assertEquals("got", lemons.getString("status"))
        // In order, and the tick on Lemons went to its real id.
        assertEquals(
            listOf("PATCH /v1/kitchen/items/$foil", "POST /v1/kitchen/items", "PATCH /v1/kitchen/items/${lemons.getString("id")}"),
            server.writes().map { "${it.method} ${it.path}" }.takeLast(3),
        )
    }

    @Test
    fun theCachedCopyIsShownOfflineAfterARestart() {
        val fetchedAt = java.time.Instant.now().minusSeconds(3_600)
        KitchenStore(context).saveCache(KitchenParser.parseSnapshot(server.snapshot()), fetchedAt)
        KitchenStore(context).outbox.enqueue(KitchenOp.ITEM_STATUS, server.item("Dish soap")!!.getString("id"), mapOf("status" to "got"), "Dish soap", fetchedAt.toString())
        server.offline = true
        launch(KitchenTab.LIST)
        waitForText("Offline: showing", substring = true)
        rule.onNodeWithText("1 change will be sent when you're back online.", substring = true).assertIsDisplayed()
        // The queued tick is shown on top of the cached copy.
        rule.onNodeWithContentDescription("Put Dish soap back on the list").assertIsDisplayed()
        rule.onNodeWithContentDescription("Tick off Aluminum foil").assertIsDisplayed()
        // Back online, the next fetch sends it.
        server.offline = false
        resumeTick = 2
        rule.waitUntil(5_000) { rule.waitForIdle(); outbox.isEmpty() }
        assertEquals("got", server.item("Dish soap")!!.getString("status"))
    }

    @Test
    fun aRefusedQueuedEditIsDroppedWithABalloonAndAGoneOneSilently() {
        launch(KitchenTab.LIST)
        waitForDescription("Tick off Aluminum foil")
        server.offline = true
        rule.onNodeWithContentDescription("Tick off Aluminum foil").performClick()
        waitForDescription("Put Aluminum foil back on the list")
        rule.onNode(hasSetTextAction()).performTextInput("Mystery item")
        rule.onNodeWithText("Add").performClick()
        waitForText("2 changes will be sent", substring = true)
        // Meanwhile the foil was removed on another device, and the bridge refuses the new name.
        server.list.removeIf { it.getString("name") == "Aluminum foil" }
        server.refuse = { method, path, body ->
            if (method == "POST" && path == "/v1/kitchen/items" && body?.optString("name") == "Mystery item") "name is not allowed" else null
        }
        server.offline = false
        rule.onNodeWithContentDescription("Refresh kitchen").performClick()
        waitForText("A kitchen change was refused")
        rule.onNodeWithText("Mystery item: name is not allowed").assertIsDisplayed()
        rule.waitUntil(5_000) { rule.waitForIdle(); outbox.isEmpty() }
        rule.waitUntil(5_000) { rule.waitForIdle(); rule.onAllNodesWithContentDescription("Tick off Mystery item").fetchSemanticsNodes().isEmpty() }
        assertTrue(server.item("Mystery item") == null)
    }

    // ------------------------------------------------------------------ offline: tried again, and handed to the background job

    private val connectivity get() = shadowOf(context.getSystemService(ConnectivityManager::class.java))

    /** Android says a network is the default one again. */
    private fun networkBack() {
        connectivity.networkCallbacks.toList().forEach { it.onAvailable(ShadowNetwork.newInstance(7)) }
        rule.waitForIdle()
    }

    @Test
    fun offlineEditsAreTriedAgainWithBackoffAndHandedToTheBackgroundJob() {
        launch(KitchenTab.LIST)
        waitForDescription("Tick off Aluminum foil")
        server.offline = true
        rule.onNodeWithContentDescription("Tick off Aluminum foil").performClick()
        waitForText("1 change will be sent", substring = true)
        assertEquals("the queue's first edit asks for the background job", 1, BridgeSyncWorker.kitchenJobs)
        rule.onNodeWithContentDescription("Tick off Dish soap").performClick()
        waitForText("2 changes will be sent", substring = true)
        assertEquals("not again for the second", 1, BridgeSyncWorker.kitchenJobs)
        // Back online: nothing is tried before the retry is due, 30 s after the failure.
        server.offline = false
        val before = gets()
        rule.mainClock.advanceTimeBy(20_000)
        rule.waitForIdle()
        assertEquals(before, gets())
        rule.mainClock.advanceTimeBy(15_000)
        rule.waitUntil(5_000) { rule.waitForIdle(); outbox.isEmpty() }
        assertEquals("got", server.item("Aluminum foil")!!.getString("status"))
        assertEquals("got", server.item("Dish soap")!!.getString("status"))
        rule.waitUntil(5_000) { rule.waitForIdle(); rule.onAllNodesWithText("Offline: showing", substring = true).fetchSemanticsNodes().isEmpty() }
        // Online again, the timer stops.
        val after = gets()
        rule.mainClock.advanceTimeBy(600_000)
        rule.waitForIdle()
        assertTrue(gets() - after <= 1)
    }

    @Test
    fun aNetworkComingBackSendsTheQueueAtOnce() {
        launch(KitchenTab.LIST)
        waitForDescription("Tick off Aluminum foil")
        assertEquals("Kitchen watches the network while it is on screen", 1, connectivity.networkCallbacks.size)
        server.offline = true
        rule.onNodeWithContentDescription("Tick off Aluminum foil").performClick()
        waitForText("1 change will be sent", substring = true)
        server.offline = false
        networkBack()
        rule.waitUntil(5_000) { rule.waitForIdle(); outbox.isEmpty() }
        assertEquals("got", server.item("Aluminum foil")!!.getString("status"))
        // Online, a network change costs nothing.
        rule.waitUntil(5_000) { rule.waitForIdle(); rule.onAllNodesWithText("Offline: showing", substring = true).fetchSemanticsNodes().isEmpty() }
        val before = gets()
        networkBack()
        assertEquals(before, gets())
        // On the reminders, nothing is watched.
        rule.onNodeWithText("Reminders").performClick()
        rule.waitForIdle()
        assertTrue(connectivity.networkCallbacks.isEmpty())
    }

    @Test
    fun aSentEditIsKeptWhenTheFetchAfterItFails() {
        launch(KitchenTab.LIST)
        waitForDescription("Tick off Aluminum foil")
        server.offline = true
        rule.onNodeWithContentDescription("Tick off Aluminum foil").performClick()
        waitForText("1 change will be sent", substring = true)
        server.offline = false
        // The tick goes through, then the connection drops before the fresh copy arrives.
        val triedOffline = server.writes().size
        server.before = { call -> if (call.method == "GET" && server.writes().size > triedOffline) throw java.io.IOException("Connection reset") }
        rule.onNodeWithContentDescription("Refresh kitchen").performClick()
        rule.waitUntil(5_000) { rule.waitForIdle(); outbox.isEmpty() }
        waitForDescription("Put Aluminum foil back on the list")
        // The phone's copy has it too, so it is not back on the list after a restart.
        rule.waitUntil(5_000) { KitchenStore(context).cached()?.first?.list?.firstOrNull { it.name == "Aluminum foil" }?.status == ListStatus.GOT }
    }

    @Test
    fun undoAfterRemovingAnOrderedLinePutsItBackOnOrder() {
        launch(KitchenTab.LIST)
        waitForDescription("Remove Salmon fillets")
        rule.onNodeWithContentDescription("Remove Salmon fillets").performClick()
        waitForText("Removed from the list")
        rule.waitUntil(5_000) { rule.waitForIdle(); server.item("Salmon fillets") == null }
        rule.onNodeWithText("Undo").performClick()
        waitForDescription("Remove Salmon fillets")
        val put = server.writes().last()
        assertEquals("POST /v1/kitchen/items", "${put.method} ${put.path}")
        assertTrue(put.body!!.getBoolean("restore"))
        assertEquals("ordered", put.body!!.getString("status"))
        assertEquals(KitchenSample.ORDER, put.body!!.getString("orderRef"))
        val back = server.item("Salmon fillets")!!
        assertEquals("ordered", back.getString("status"))
        assertEquals(KitchenSample.ORDER, back.getString("orderRef"))
        // Listed under Ordered again, with its order, and not counted as to buy.
        rule.onNodeWithText("Shopping list (6)").assertIsDisplayed()
        assertEquals(2, rule.onAllNodesWithText(KitchenSample.ORDER, substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun aRefusedNoteKeepsTheDetailsOpenWithTheReason() {
        server.notes.clear()
        server.refuse = { method, _, _ -> if (method == "PUT") "note is too long" else null }
        launch(KitchenTab.WEEK)
        waitForText("Chicken jambalaya")
        rule.onNodeWithText("Chicken jambalaya").performClick()
        waitForText("How did it go?")
        rule.onNodeWithText("Not again").performScrollTo().performClick()
        rule.onNodeWithText("Save").performClick()
        waitForText("note is too long")
        rule.onNodeWithText("How did it go?").assertIsDisplayed()
        assertTrue(server.notes.isEmpty())
        assertTrue("a refusal is not queued", outbox.isEmpty())
    }

    @Test
    fun anOfflineNoteIsQueuedAndTheDetailsClose() {
        server.notes.clear()
        launch(KitchenTab.WEEK)
        waitForText("Chicken jambalaya")
        server.offline = true
        rule.onNodeWithText("Chicken jambalaya").performClick()
        waitForText("How did it go?")
        rule.onNodeWithText("Not again").performScrollTo().performClick()
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(5_000) { rule.waitForIdle(); rule.onAllNodesWithText("How did it go?").fetchSemanticsNodes().isEmpty() }
        waitForText("Not again")
        assertEquals(KitchenOp.NOTE, outbox.all().single().op)
        assertTrue(server.notes.isEmpty())
    }

    // ------------------------------------------------------------------ on hand: Claude's estimate and corrections

    private fun shelf(name: String) = server.pantry.first { it.getString("name") == name }

    /** On hand lists the box and the corrections first; scroll the estimate to [name]'s row. */
    private fun showShelf(name: String) = showKey("estimate-" + shelf(name).getString("id"))

    private fun showKey(key: String) {
        rule.waitUntil(5_000) { rule.waitForIdle(); rule.onAllNodes(hasScrollToKeyAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasScrollToKeyAction()).performScrollToKey(key)
        rule.waitForIdle()
    }

    @Test
    fun weAreOutShowsTheReplyAndPutsItOnTheList() {
        launch(KitchenTab.PANTRY)
        waitForText("Your corrections")
        showShelf("Black beans")
        rule.onNodeWithText("2 cans, bought 10/26").assertIsDisplayed()
        rule.onNodeWithContentDescription("Black beans: Have").performClick()
        waitForText("We're out")
        rule.onNodeWithText("We're out").performClick()
        // The reply, the estimate, the correction in the list, and the shopping list.
        waitForText("Marked out and added to the shopping list.")
        val call = server.writes().single()
        assertEquals("POST /v1/kitchen/corrections", "${call.method} ${call.path}")
        assertEquals("out", call.body!!.getString("kind"))
        assertEquals(shelf("Black beans").getString("id"), call.body!!.getString("itemId"))
        showShelf("Black beans")
        waitForDescription("Black beans: Probably out")
        rule.onAllNodesWithText("Pixel 10 Pro XL said it's out").assertCountEquals(2)
        showKey("corrections-header")
        waitForText("Applied: Marked out and added to the shopping list.")
        // In the list of corrections (and in the balloon's title).
        assertTrue(rule.onAllNodesWithText("We're out: Black beans").fetchSemanticsNodes().isNotEmpty())
        assertEquals("pantry", server.item("Black beans")!!.getString("source"))
        // Shown at once from the answer, without another fetch.
        assertEquals(1, gets())
        rule.onNodeWithText("Shopping list (7)").performClick()
        waitForDescription("Tick off Black beans")
    }

    @Test
    fun weHaveItSetsTheEstimate() {
        launch(KitchenTab.PANTRY)
        waitForText("Your corrections")
        showShelf("Onions")
        rule.onNodeWithContentDescription("Onions: Running low").performClick()
        rule.onNodeWithText("We have it").performClick()
        waitForText("Marked as on hand.")
        showShelf("Onions")
        waitForDescription("Onions: Have")
        assertEquals("have", server.writes().single().body!!.getString("kind"))
    }

    @Test
    fun aSentNoteShowsAsOpenAndCanBeWithdrawn() {
        launch(KitchenTab.PANTRY)
        waitForText("Your corrections")
        rule.onNode(hasSetTextAction()).performTextInput("Tossed the old lettuce")
        rule.onNodeWithText("Send").performClick()
        waitForText("“Tossed the old lettuce”")
        val call = server.writes().single()
        assertEquals("POST /v1/kitchen/corrections", "${call.method} ${call.path}")
        assertEquals("note", call.body!!.getString("kind"))
        assertEquals("Tossed the old lettuce", call.body!!.getString("text"))
        assertTrue("a plain note is about no item", !call.body!!.has("itemId"))
        // Open, waiting for Claude, and the box is empty again.
        assertEquals(2, rule.onAllNodesWithText("Waiting for Claude").fetchSemanticsNodes().size)
        rule.waitUntil(5_000) { rule.waitForIdle(); rule.onAllNodesWithText("Tossed the old lettuce").fetchSemanticsNodes().isEmpty() }
        val id = server.correctionAbout("Tossed the old lettuce")!!.getString("id")
        rule.onAllNodesWithText("Withdraw").onLast().performClick()
        rule.waitUntil(5_000) { rule.waitForIdle(); rule.onAllNodesWithText("“Tossed the old lettuce”").fetchSemanticsNodes().isEmpty() }
        assertEquals("DELETE /v1/kitchen/corrections/$id", server.writes().last().let { "${it.method} ${it.path}" })
        assertTrue(server.correctionAbout("Tossed the old lettuce") == null)
    }

    @Test
    fun somethingElseWritesANoteAboutTheItem() {
        launch(KitchenTab.PANTRY)
        waitForText("Your corrections")
        showShelf("Diced tomatoes")
        rule.onNodeWithContentDescription("Diced tomatoes: Running low").performClick()
        rule.onNodeWithText("Something else…").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("About Diced tomatoes", substring = true).assertIsDisplayed()
        rule.onNode(hasSetTextAction()).performTextInput("3 cans in the garage")
        rule.onNodeWithText("Send").performClick()
        rule.waitUntil(5_000) { rule.waitForIdle(); server.correctionAbout("Diced tomatoes: 3 cans in the garage") != null }
        showKey("corrections-header")
        waitForText("\u201CDiced tomatoes: 3 cans in the garage\u201D")
        val body = server.writes().single().body!!
        assertEquals("Diced tomatoes: 3 cans in the garage", body.getString("text"))
        assertEquals(shelf("Diced tomatoes").getString("id"), body.getString("itemId"))
    }

    @Test
    fun aNoteSentOfflineIsQueuedAndSentLater() {
        launch(KitchenTab.PANTRY)
        waitForText("Your corrections")
        server.offline = true
        rule.onNode(hasSetTextAction()).performTextInput("Out of coffee filters")
        rule.onNodeWithText("Send").performClick()
        waitForText("Not sent yet")
        waitForText("1 change will be sent when you're back online.", substring = true)
        rule.onNodeWithText("“Out of coffee filters”").assertIsDisplayed()
        assertEquals(KitchenOp.CORRECTION, outbox.all().single().op)
        assertTrue(server.correctionAbout("Out of coffee filters") == null)
        server.offline = false
        rule.onNodeWithContentDescription("Refresh kitchen").performClick()
        rule.waitUntil(5_000) { rule.waitForIdle(); outbox.isEmpty() }
        rule.waitUntil(5_000) { rule.waitForIdle(); rule.onAllNodesWithText("Not sent yet").fetchSemanticsNodes().isEmpty() }
        assertEquals("open", server.correctionAbout("Out of coffee filters")!!.getString("status"))
        assertEquals(2, rule.onAllNodesWithText("Waiting for Claude").fetchSemanticsNodes().size)
    }

    @Test
    fun aNoteWithdrawnBeforeItWasSentNeverLeavesThePhone() {
        launch(KitchenTab.PANTRY)
        waitForText("Your corrections")
        server.offline = true
        rule.onNode(hasSetTextAction()).performTextInput("Never mind")
        rule.onNodeWithText("Send").performClick()
        waitForText("Not sent yet")
        rule.onAllNodesWithText("Withdraw").onLast().performClick()
        rule.waitUntil(5_000) { rule.waitForIdle(); rule.onAllNodesWithText("Not sent yet").fetchSemanticsNodes().isEmpty() }
        assertTrue(outbox.isEmpty())
        server.offline = false
        rule.onNodeWithContentDescription("Refresh kitchen").performClick()
        rule.waitUntil(5_000) { rule.waitForIdle(); rule.onAllNodesWithText("Offline: showing", substring = true).fetchSemanticsNodes().isEmpty() }
        assertTrue(server.correctionAbout("Never mind") == null)
    }

    @Test
    fun withdrawIsOfferedForNotesOnly() {
        launch(KitchenTab.PANTRY)
        waitForText("Your corrections")
        assertEquals("the open note", 1, rule.onAllNodesWithText("Withdraw").fetchSemanticsNodes().size)
        server.offline = true
        showShelf("Onions")
        rule.onNodeWithContentDescription("Onions: Running low").performClick()
        rule.onNodeWithText("We're out").performClick()
        showKey("corrections-header")
        waitForText("Not sent yet")
        assertEquals("a queued We're out is not offered for withdrawal", 1, rule.onAllNodesWithText("Withdraw").fetchSemanticsNodes().size)
    }

    @Test
    fun weHaveItTakesThePantrysLineOffTheShoppingList() {
        launch(KitchenTab.PANTRY)
        waitForText("Your corrections")
        rule.onNodeWithText("Shopping list (6)").assertIsDisplayed()
        showShelf("Eggs")
        rule.onNodeWithContentDescription("Eggs: Probably out").performClick()
        rule.onNodeWithText("We have it").performClick()
        waitForText("Marked as on hand and taken off the shopping list.")
        rule.onNodeWithText("Shopping list (5)").assertIsDisplayed()
        assertTrue(server.item("Eggs") == null)
        assertEquals("shown from the answer, without another fetch", 1, gets())
    }

    @Test
    fun aQueuedNoteAboutAnItemRemovedMeanwhileStillReachesClaude() {
        // Bridge 0.10.0 refuses a note about an item it no longer has.
        server.bridge011 = false
        launch(KitchenTab.PANTRY)
        waitForText("Your corrections")
        showShelf("Diced tomatoes")
        rule.onNodeWithContentDescription("Diced tomatoes: Running low").performClick()
        rule.onNodeWithText("Something else…").performClick()
        rule.waitForIdle()
        server.offline = true
        rule.onNode(hasSetTextAction()).performTextInput("3 cans in the garage")
        rule.onNodeWithText("Send").performClick()
        showKey("corrections-header")
        waitForText("Not sent yet")
        // Claude removes the item meanwhile.
        server.pantry.removeIf { it.getString("name") == "Diced tomatoes" }
        server.offline = false
        rule.onNodeWithContentDescription("Refresh kitchen").performClick()
        rule.waitUntil(5_000) { rule.waitForIdle(); outbox.isEmpty() }
        // The first try was the one that found no connection; then about the item, then without it.
        val posts = server.writes().filter { it.path == "/v1/kitchen/corrections" }.drop(1)
        assertEquals(listOf(true, false), posts.map { it.body!!.has("itemId") })
        assertEquals("open", server.correctionAbout("Diced tomatoes: 3 cans in the garage")!!.getString("status"))
        assertTrue(rule.onAllNodesWithText("A kitchen change was refused").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aCorrectionWhoseAnswerWasLostIsStoredOnce() {
        launch(KitchenTab.PANTRY)
        waitForText("Your corrections")
        // The cloud stores the note, but its answer never reaches the phone.
        var lose = true
        server.after = { call ->
            if (lose && call.path == "/v1/kitchen/corrections") {
                lose = false
                throw java.io.IOException("timeout")
            }
        }
        rule.onNode(hasSetTextAction()).performTextInput("Out of coffee filters")
        rule.onNodeWithText("Send").performClick()
        waitForText("Not sent yet")
        rule.onNodeWithContentDescription("Refresh kitchen").performClick()
        rule.waitUntil(5_000) { rule.waitForIdle(); outbox.isEmpty() }
        val posts = server.writes().filter { it.path == "/v1/kitchen/corrections" }
        assertEquals(2, posts.size)
        assertEquals("sent again under the same clientId", posts[0].body!!.getString("clientId"), posts[1].body!!.getString("clientId"))
        assertEquals("stored once", 1, server.corrections.count { it.optString("text") == "Out of coffee filters" })
        rule.waitUntil(5_000) { rule.waitForIdle(); rule.onAllNodesWithText("Not sent yet").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, rule.onAllNodesWithText("“Out of coffee filters”").fetchSemanticsNodes().size)
    }

    @Test
    fun theNewestThreeCorrectionsShowWithShowAll() {
        launch(KitchenTab.PANTRY)
        waitForText("Your corrections")
        rule.onNodeWithText("“We have 2 cans of chickpeas in the garage”").assertIsDisplayed()
        rule.onNodeWithText("Claude: Removed cilantro from the pantry.").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("“Is there any rice left?”").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("Show all (4)").performClick()
        waitForText("“Is there any rice left?”")
        rule.onNodeWithText("Show fewer").assertIsDisplayed()
    }

    @Test
    fun theFilterShowsOnlyWhatIsRunningLowOrOut() {
        launch(KitchenTab.PANTRY)
        waitForText("Your corrections")
        showKey("estimate-header")
        rule.onNodeWithText("Running low & out only").performClick()
        rule.waitUntil(5_000) { rule.waitForIdle(); rule.onAllNodesWithContentDescription("Black beans: Have").fetchSemanticsNodes().isEmpty() }
        showShelf("Eggs")
        rule.onNodeWithContentDescription("Eggs: Probably out").assertIsDisplayed()
        showShelf("Diced tomatoes")
        rule.onNodeWithContentDescription("Diced tomatoes: Running low").assertIsDisplayed()
    }

    @Test
    fun theEstimateSaysWhenClaudeLastLooked() {
        launch(KitchenTab.PANTRY)
        waitForText("What Claude thinks you have. Updated", substring = true)
        val checked = java.time.Instant.parse(server.pantry.first().getString("checkedAt")).atZone(java.time.ZoneId.systemDefault())
        rule.onNodeWithText("What Claude thinks you have. Updated ${checked.format(java.time.format.DateTimeFormatter.ofPattern("EEE M/d"))}.").assertIsDisplayed()
    }

    @Test
    fun anEmptyEstimateSaysWhenItWillBeFilled() {
        server.pantry.clear()
        server.corrections.clear()
        launch(KitchenTab.PANTRY)
        waitForText("Claude fills this in after the next grocery order.")
        rule.onNodeWithText("What Claude thinks you have.").assertIsDisplayed()
    }

    // ------------------------------------------------------------------ kitchen_sync

    @Test
    fun aKitchenSyncSignalRefetchesOnlyWhileKitchenIsShowing() {
        launch(tab = null)
        KitchenSync.signal()
        rule.waitForIdle()
        assertEquals("Kitchen closed: the signal does nothing", 0, gets())
        rule.onNodeWithText("Kitchen").performClick()
        rule.waitUntil(5_000) { rule.waitForIdle(); gets() == 1 }
        waitForText("Shopping list (6)")
        // Another device added something.
        server.listItem("Lemons", addedAt = server.now())
        server.revision++
        KitchenSync.signal()
        waitForText("Shopping list (7)")
        assertEquals(2, gets())
        // A burst: one fetch running, one queued, no more.
        repeat(6) { KitchenSync.signal() }
        rule.waitForIdle()
        Thread.sleep(300)
        rule.waitForIdle()
        assertTrue("a burst of 6 cost ${gets() - 2} fetches", gets() - 2 in 1..2)
        // Back on the reminders, signals are ignored again.
        rule.onNodeWithText("Reminders").performClick()
        rule.waitForIdle()
        val before = gets()
        KitchenSync.signal()
        rule.waitForIdle()
        assertEquals(before, gets())
    }

    @Test
    fun withoutPairingKitchenSaysItNeedsAConnection() {
        launch(KitchenTab.WEEK, connected = false)
        waitForText(KITCHEN_OFFLINE)
        rule.onNodeWithText("Connect this phone").assertIsDisplayed()
        assertTrue("nothing is sent without a pairing", server.calls.isEmpty())
    }

    @Test
    fun offlineKitchenSaysSoAndCanTryAgain() {
        server.offline = true
        launch(KitchenTab.WEEK)
        waitForText(KITCHEN_OFFLINE)
        server.offline = false
        rule.onNodeWithText("Try again").performClick()
        waitForText("Tonight")
    }

    @Test
    fun refreshAndResumeFetchAgain() {
        launch(KitchenTab.WEEK)
        waitForText("Tonight")
        assertEquals(1, gets())
        rule.onNodeWithContentDescription("Refresh kitchen").performClick()
        rule.waitUntil(5_000) { rule.waitForIdle(); gets() == 2 }
        resumeTick = 2
        rule.waitUntil(5_000) { rule.waitForIdle(); gets() == 3 }
        // A change made elsewhere shows up on the next fetch.
        server.listItem("Lemons", addedAt = server.now())
        server.revision++
        rule.onNodeWithContentDescription("Refresh kitchen").performClick()
        rule.waitUntil(5_000) { rule.waitForIdle(); rule.onAllNodesWithText("Shopping list (7)").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun kitchenNeverTouchesRemindersAlarmsOrTheOutbox() {
        launch(KitchenTab.LIST)
        waitForDescription("Tick off Aluminum foil")
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size
        val stored = EventRepository(context).loadEvents()
        rule.onNodeWithContentDescription("Tick off Aluminum foil").performClick()
        waitForDescription("Put Aluminum foil back on the list")
        rule.onNode(hasSetTextAction()).performTextInput("Lemons")
        rule.onNodeWithText("Add").performClick()
        waitForDescription("Tick off Lemons")
        rule.onNodeWithText("On hand").performClick()
        showShelf("Onions")
        rule.onNodeWithContentDescription("Onions: Running low").performClick()
        rule.onNodeWithText("We're out").performClick()
        rule.waitUntil(5_000) { rule.waitForIdle(); shelf("Onions").getString("status") == "out" }
        showKey("corrections-header")
        rule.onNode(hasSetTextAction()).performTextInput("Out of coffee")
        rule.onNodeWithText("Send").performClick()
        waitForText("\u201COut of coffee\u201D")
        assertEquals(stored, EventRepository(context).loadEvents())
        assertEquals(alarms, shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
        assertTrue(PendingChangeStore(context).isEmpty())
        assertTrue(BridgeApi.doneCalls.isEmpty())
        assertTrue(BridgeApi.seriesCalls.isEmpty())
        assertTrue(server.calls.all { it.path.startsWith("/v1/kitchen") })
    }

    @Test
    fun aNotificationTapLeavesKitchenForTheReminder() {
        var open by androidx.compose.runtime.mutableStateOf<String?>(null)
        EventRepository(context).saveEvents(reminders)
        BridgeSettings(context).saveConfig(BridgeConfig("https://example.invalid/api", "p", "a", "k", "s"))
        BridgeSettings(context).saveDeviceToken("token")
        prefs.edit().putBoolean("kitchen", true).putString("kitchen_tab", KitchenTab.WEEK.name).commit()
        rule.setContent {
            XpTheme {
                CompositionLocalProvider(LocalXpInlineDialogs provides true) {
                    PlannerApp(resumeTick = 1, openEventId = open, onOpenEventHandled = { open = null }, onMinimize = {}, onExit = {})
                }
            }
        }
        waitForText("Tonight")
        open = "rent"
        rule.waitForIdle()
        rule.onNodeWithText("Financial - Cloud", substring = true).assertIsDisplayed()
        assertFalse(prefs.getBoolean("kitchen", true))
        rule.onAllNodesWithText("Tonight").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        rule.onAllNodesWithText("Rent due").onLast().assertIsDisplayed()
    }
}
