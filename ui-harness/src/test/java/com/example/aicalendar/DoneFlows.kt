package com.example.aicalendar

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.aicalendar.xp.LocalXpInlineDialogs
import com.example.aicalendar.xp.XpTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/** HARNESS ONLY: ticking a cloud reminder sends Done or Not done, never this phone's copy of the rest. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w443dp-h986dp-420dpi")
class DoneFlows {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Denver"))
        context = RuntimeEnvironment.getApplication()
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        BridgeApi.doneCalls.clear()
        val start = LocalDate.now().plusDays(2).atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        EventRepository(context).saveEvents(listOf(
            PlannerEvent(id = "cloud-1", title = "Dentist", startAtMillis = start, remote = true, source = "chatgpt", revision = 42),
        ))
        BridgeSettings(context).saveConfig(BridgeConfig("https://example.invalid/api", "p", "a", "k", "s"))
        BridgeSettings(context).saveDeviceToken("token")
        rule.setContent {
            XpTheme {
                CompositionLocalProvider(LocalXpInlineDialogs provides true) {
                    PlannerApp(resumeTick = 1, openEventId = null, onOpenEventHandled = {}, onMinimize = {}, onExit = {})
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun tickingAndUndoSendOnlyDoneAndNotDone() {
        rule.onAllNodesWithContentDescription("Mark done").onFirst().performClick()
        rule.waitForIdle()
        assertEquals(listOf("cloud-1" to true), BridgeApi.doneCalls)
        rule.onNodeWithText("Undo").performClick()
        // The stand-in bridge answers on a background thread: wait for the phone copy to follow.
        rule.waitUntil(5_000) {
            rule.waitForIdle()
            EventRepository(context).find("cloud-1")?.done == false
        }
        assertEquals(listOf("cloud-1" to true, "cloud-1" to false), BridgeApi.doneCalls)
        assertEquals(false, EventRepository(context).find("cloud-1")?.done)
    }
}
