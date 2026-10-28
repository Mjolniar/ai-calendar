package com.example.aicalendar

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.aicalendar.xp.LocalXpInlineDialogs
import com.example.aicalendar.xp.XpTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import java.util.TimeZone

/** HARNESS ONLY: the update prompts wait for the user, and the install carries on after "Allow from this source". */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w443dp-h986dp-420dpi")
class UpdateFlows {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var context: Context
    private var resumeTick by mutableIntStateOf(1)
    private val release = AppRelease(30, "0.18.0", "a".repeat(64), 15_000_000)

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Denver"))
        context = RuntimeEnvironment.getApplication()
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        BridgeApi.release = null
        BridgeApi.downloads.clear()
        canInstall(false)
    }

    @After
    fun tearDown() {
        BridgeApi.release = null
        BridgeApi.downloads.clear()
        BridgeApi.kitchen = FakeKitchenServer()
        // The permission lives in a shadow shared by the tests that follow.
        canInstall(false)
    }

    /** The screen's own context, whose package manager the app asks. */
    private var screen: Context? = null

    private fun canInstall(allowed: Boolean) {
        shadowOf(context.packageManager).setCanRequestPackageInstalls(allowed)
        screen?.let { shadowOf(it.packageManager).setCanRequestPackageInstalls(allowed) }
    }

    private fun launch(paired: Boolean = true) {
        if (paired) {
            BridgeSettings(context).saveConfig(BridgeConfig("https://example.invalid/api", "p", "a", "k", "s"))
            BridgeSettings(context).saveDeviceToken("token")
        }
        rule.setContent {
            screen = androidx.compose.ui.platform.LocalContext.current
            XpTheme {
                CompositionLocalProvider(LocalXpInlineDialogs provides true) {
                    PlannerApp(resumeTick = resumeTick, openEventId = null, onOpenEventHandled = {}, onMinimize = {}, onExit = {})
                }
            }
        }
        rule.waitForIdle()
    }

    private fun waitForText(text: String) =
        rule.waitUntil(5_000) {
            rule.waitForIdle()
            rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }

    private fun shown(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theUpdateBalloonStaysUntilItIsUsed() {
        BridgeApi.release = release
        launch()
        waitForText("Update available")
        rule.onNodeWithText("AI Planner 0.18.0 is ready to install.").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(30_000)
        rule.waitForIdle()
        assertTrue("an Install prompt does not time out", shown("Update available"))
        rule.onNodeWithText("Install").assertIsDisplayed()
    }

    @Test
    fun aBalloonWithoutAnActionStillCloses() {
        launch()
        rule.onNodeWithText("Help").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Check for Updates...").performClick()
        waitForText("No update")
        rule.mainClock.advanceTimeBy(30_000)
        rule.waitForIdle()
        assertTrue(!shown("No update"))
    }

    @Test
    fun aPassingBalloonWaitsBehindTheUpdatePrompt() {
        val kitchen = KitchenSample.server()
        BridgeApi.kitchen = kitchen
        context.getSharedPreferences("planner_view", Context.MODE_PRIVATE).edit()
            .putBoolean("kitchen", true).putString("kitchen_tab", KitchenTab.LIST.name).commit()
        BridgeApi.release = release
        launch()
        waitForText("Update available")
        rule.waitUntil(5_000) { rule.waitForIdle(); rule.onAllNodesWithContentDescription("Remove Dish soap").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Remove Dish soap").performClick()
        rule.waitUntil(5_000) { rule.waitForIdle(); kitchen.item("Dish soap") == null }
        rule.waitForIdle()
        assertTrue("the update prompt is not covered", shown("Update available"))
        assertTrue(!shown("Removed from the list"))
        // Closing the prompt (the balloon's own close box, drawn last) shows the balloon that waited.
        rule.onAllNodesWithContentDescription("Close").onLast().performClick()
        waitForText("Removed from the list")
        assertTrue(!shown("Update available"))
    }

    @Test
    fun aWaitingInstallThatIsNoLongerNewerIsForgotten() {
        // It waited for the permission; meanwhile that build went on by USB.
        AppUpdater.setPendingInstall(context, release.copy(versionCode = 1))
        canInstall(true)
        launch()
        rule.waitForIdle()
        assertTrue("nothing is downloaded", BridgeApi.downloads.isEmpty())
        assertNull(AppUpdater.pendingInstall(context))
    }

    @Test
    fun aWaitingInstallIsForgottenOnceThePhoneIsUnpaired() {
        AppUpdater.setPendingInstall(context, release)
        canInstall(true)
        launch(paired = false)
        rule.waitForIdle()
        assertTrue(BridgeApi.downloads.isEmpty())
        assertNull(AppUpdater.pendingInstall(context))
    }

    @Test
    fun grantingThePermissionContinuesTheInstall() {
        BridgeApi.release = release
        launch()
        waitForText("Update available")
        rule.onNodeWithText("Install").performClick()
        waitForText("Allow updates first")
        assertTrue("nothing is downloaded without the permission", BridgeApi.downloads.isEmpty())
        assertNotNull(AppUpdater.pendingInstall(context))
        rule.mainClock.advanceTimeBy(30_000)
        rule.waitForIdle()
        assertTrue("the Settings prompt waits too", shown("Allow updates first"))

        // Back from Android's settings without allowing it: nothing happens, the prompt stays.
        resumeTick = 2
        rule.waitForIdle()
        assertTrue(BridgeApi.downloads.isEmpty())
        assertTrue(shown("Allow updates first"))

        // Back with it allowed: the install carries on by itself, no second Install tap.
        canInstall(true)
        resumeTick = 3
        rule.waitUntil(5_000) {
            rule.waitForIdle()
            BridgeApi.downloads.isNotEmpty()
        }
        assertEquals(listOf(release), BridgeApi.downloads)
        assertNull(AppUpdater.pendingInstall(context))
        rule.waitUntil(5_000) {
            rule.waitForIdle()
            !shown("Allow updates first")
        }
    }
}
