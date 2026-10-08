package com.example.aicalendar

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.example.aicalendar.xp.XpTheme
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

/** HARNESS ONLY: fakes status/navigation bar insets to preview how the chrome extends under them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w443dp-h986dp-420dpi")
class XpInsetsShot {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun withSystemBars() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Denver"))
        val context: Context = RuntimeEnvironment.getApplication()
        fun at(days: Long, hour: Int) = LocalDate.now().plusDays(days).atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        EventRepository(context).saveEvents(
            listOf(
                PlannerEvent(id = "1", title = "Plumber visit", startAtMillis = at(1, 9), critical = true, category = EventCategory.HOUSING),
                PlannerEvent(id = "2", title = "Rent due", startAtMillis = at(4, 9), category = EventCategory.FINANCIAL),
            ),
        )
        rule.setContent {
            XpTheme { PlannerApp(resumeTick = 1, openEventId = null, onOpenEventHandled = {}, onMinimize = {}, onExit = {}) }
        }
        rule.waitForIdle()
        val density = context.resources.displayMetrics.density
        rule.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, (50 * density).toInt(), 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, (48 * density).toInt()))
                .build()
            val decor = rule.activity.window.decorView
            ViewCompat.dispatchApplyWindowInsets(decor, insets)
            val content = rule.activity.findViewById<android.view.ViewGroup>(android.R.id.content)
            for (i in 0 until content.childCount) ViewCompat.dispatchApplyWindowInsets(content.getChildAt(i), insets)
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/24-insets.png", RoborazziOptions(recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)))
    }
}
