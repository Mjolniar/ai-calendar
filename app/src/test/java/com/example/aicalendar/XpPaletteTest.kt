package com.example.aicalendar

import androidx.compose.ui.graphics.Color
import com.example.aicalendar.xp.XpAppearance
import com.example.aicalendar.xp.XpColors
import com.example.aicalendar.xp.XpPalette
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** Both palettes must keep text readable: WCAG contrast of 4.5:1 for text, 7:1 for the main ink. */
class XpPaletteTest {
    private val palettes = listOf("Luna" to XpPalette.Luna, "Noir" to XpPalette.Noir)

    @After
    fun tearDown() {
        XpAppearance.dark = false
    }

    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val v = value.toDouble()
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val high = maxOf(luminance(a), luminance(b))
        val low = minOf(luminance(a), luminance(b))
        return (high + 0.05) / (low + 0.05)
    }

    private fun check(name: String, what: String, ink: Color, paper: Color, minimum: Double) {
        val ratio = contrast(ink, paper)
        assertTrue("$name: $what is %.2f:1, needs $minimum:1".format(ratio), ratio >= minimum)
    }

    @Test
    fun mainTextIsHighContrastOnEverySurface() {
        for ((name, p) in palettes) {
            check(name, "text on face", p.text, p.face, 7.0)
            check(name, "text on details panel", p.text, p.faceLight, 7.0)
            check(name, "text on list", p.text, p.window, 7.0)
            check(name, "text on menu", p.text, p.menu, 7.0)
            check(name, "balloon text", p.tooltipText, p.tooltip, 7.0)
        }
    }

    @Test
    fun secondaryAndColoredTextStaysReadable() {
        for ((name, p) in palettes) {
            check(name, "dim text on face", p.textDim, p.face, 4.5)
            check(name, "dim text on details panel", p.textDim, p.faceLight, 4.5)
            check(name, "dim text on list", p.textDim, p.window, 4.5)
            check(name, "dim text on balloon", p.textDim, p.tooltip, 4.5)
            check(name, "link on list", p.link, p.window, 4.5)
            check(name, "link on face", p.link, p.face, 4.5)
            check(name, "link on balloon", p.link, p.tooltip, 4.5)
            check(name, "group caption on face", p.groupText, p.face, 4.5)
            check(name, "weekday names on list", p.groupText, p.window, 4.5)
            check(name, "list group caption", p.headerText, p.window, 4.5)
            check(name, "error text on face", p.errorText, p.face, 4.5)
            check(name, "tag text", p.tagText, p.tagFill, 4.5)
            check(name, "selected row text", Color.White, p.highlight, 4.5)
        }
    }

    @Test
    fun controlEdgesAreVisible() {
        for ((name, p) in palettes) {
            check(name, "field border on face", p.fieldBorder, p.face, 2.0)
            check(name, "field border on list", p.fieldBorder, p.window, 2.0)
            check(name, "check box border on list", p.checkBorder, p.window, 3.0)
            check(name, "today ring on list", p.today, p.window, 3.0)
            check(name, "menu edge on list", p.menuBorder, p.window, 1.5)
        }
    }

    @Test
    fun darkSwitchSelectsThePalette() {
        assertSame(XpPalette.Luna, XpColors)
        XpAppearance.dark = true
        assertSame(XpPalette.Noir, XpColors)
        assertTrue(XpColors.dark)
        XpAppearance.dark = false
        assertSame(XpPalette.Luna, XpColors)
        assertFalse(XpColors.dark)
    }
}
