package com.example.aicalendar.xp

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Every color the XP controls draw with. [Luna] is the classic look: beige surfaces, white lists,
 * blue chrome. [Noir] is the same chrome in charcoal, in the manner of XP's dark visual styles.
 * Controls never hold a color of their own, so adding a palette here restyles the whole app.
 */
@Immutable
class XpPalette(
    val dark: Boolean,

    // Surfaces
    /** Dialogs, bars and the menu bar. */
    val face: Color,
    /** The details panel under a selected row. */
    val faceLight: Color,
    /** Lists and edit fields. */
    val window: Color,
    val menu: Color,
    /** Balloon tips and information bars. */
    val tooltip: Color,

    // Text
    val text: Color,
    val textDim: Color,
    val textDisabled: Color,
    val tooltipText: Color,
    val errorText: Color,
    val link: Color,
    /** Group box captions and weekday names. */
    val groupText: Color,
    /** Captions of the list groups ("Today", "Next week"). */
    val headerText: Color,
    val tagText: Color,

    // Lines and borders
    /** Dark edge under bars; the dark half of an etched line. */
    val shadow: Color,
    /** The light half of an etched line. */
    val etchLight: Color,
    /** Hairline between list rows. */
    val divider: Color,
    val fieldBorder: Color,
    val buttonBorder: Color,
    val disabledBorder: Color,
    val groupBorder: Color,
    val tabBorder: Color,
    val menuBorder: Color,
    val tooltipBorder: Color,
    val headerRule: Color,
    val tagBorder: Color,
    /** Edge that keeps a color chip visible on any background. */
    val swatchEdge: Color,
    val windowFrame: Color,

    // Accents
    val highlight: Color,
    /** Today's ring in the calendar and the dot of a critical reminder. */
    val today: Color,
    val tabAccent: Color,
    val tabAccentLight: Color,
    val tagFill: Color,
    val checkBorder: Color,
    val checkGreen: Color,
    /** Bright centre of a selected radio button. */
    val checkGlint: Color,
    /** Claude's estimate on the On hand chips: running low, and probably out (Have is [checkGreen]). */
    val estimateWarn: Color,
    val estimateCritical: Color,

    // Control faces
    val buttonFill: Brush,
    val buttonPressedFill: Brush,
    val buttonDisabledFill: Brush,
    /** Ring inside the default button of a dialog. */
    val defaultRing: Brush,
    val checkFill: Brush,
    val checkPressedFill: Brush,
    val tabFill: Brush,
    val tabSelected: Color,
    val dropFill: Brush,
    val dropPressedFill: Brush,
    val dropBorder: Color,
    val dropChevron: Color,
    val scrollTrack: Brush,
    val scrollTrackEdge: Color,
    val scrollThumb: Brush,
    val scrollThumbBorder: Color,
    val scrollGripLight: Color,
    val scrollGripDark: Color,

    // Window caption
    val titleStops: Array<Pair<Float, Color>>,
    /** Glossy band along the top of the caption, top to bottom. */
    val titleGloss: List<Color>,
    /** Dark line along the bottom of the caption, top to bottom. */
    val titleEdge: List<Color>,
    val titleShadow: Color,
    /** Minimize and Maximize buttons: deepest at the bottom-right, running out to a glint at the top-left. */
    val captionStops: Array<Pair<Float, Color>>,
    val captionPressedStops: Array<Pair<Float, Color>>,
    val captionEdge: Color,

    // Taskbar
    val taskbarStops: Array<Pair<Float, Color>>,
    val taskbarGloss: Array<Pair<Float, Color>>,
    val taskbarEdge: List<Color>,
    /** Plate behind a pressed or active taskbar icon. */
    val taskbarSunken: Color,
    val taskbarSunkenBorder: Color,
    val taskbarGrooveDark: Color,
    val taskbarGrooveLight: Color,
    val trayStops: Array<Pair<Float, Color>>,
    val trayTopLine: Color,
    val trayLeftLine: Color,
    val trayGlint: Color,
) {
    companion object {
        val Luna = XpPalette(
            dark = false,

            face = Color(0xFFECE9D8),
            faceLight = Color(0xFFF6F5EE),
            window = Color(0xFFFFFFFF),
            menu = Color(0xFFFFFFFF),
            tooltip = Color(0xFFFFFFE1),

            text = Color(0xFF000000),
            textDim = Color(0xFF5B5B5B),
            textDisabled = Color(0xFFA8A595),
            tooltipText = Color(0xFF000000),
            errorText = Color(0xFFB00000),
            link = Color(0xFF0046D5),
            groupText = Color(0xFF0046D5),
            headerText = Color(0xFF0C327D),
            tagText = Color(0xFF7A4A00),

            shadow = Color(0xFFACA899),
            etchLight = Color(0xFFFFFFFF),
            divider = Color(0xFFE4E2D6),
            fieldBorder = Color(0xFF7F9DB9),
            buttonBorder = Color(0xFF003C74),
            disabledBorder = Color(0xFFC9C7BA),
            groupBorder = Color(0xFFD0D0BF),
            tabBorder = Color(0xFF919B9C),
            menuBorder = Color(0xFFACA899),
            tooltipBorder = Color(0xFF000000),
            headerRule = Color(0xFF7A96DF),
            tagBorder = Color(0xFFD9A441),
            swatchEdge = Color.Black.copy(alpha = 0.42f),
            windowFrame = Color(0xFF0831D9),

            highlight = Color(0xFF316AC5),
            today = Color(0xFFD8321A),
            tabAccent = Color(0xFFE68B2C),
            tabAccentLight = Color(0xFFFFC73C),
            tagFill = Color(0xFFFFF1C2),
            checkBorder = Color(0xFF1C5180),
            checkGreen = Color(0xFF21A121),
            checkGlint = Color(0xFF8CDB6A),
            estimateWarn = Color(0xFFD9A441),
            estimateCritical = Color(0xFFD8321A),

            buttonFill = Brush.verticalGradient(0f to Color.White, 0.86f to Color(0xFFECEBE5), 1f to Color(0xFFD8D0C4)),
            buttonPressedFill = Brush.verticalGradient(
                0f to Color(0xFFCDCAC3), 0.08f to Color(0xFFE3E3DB), 0.94f to Color(0xFFE5E5DE), 1f to Color(0xFFF2F2F1),
            ),
            buttonDisabledFill = SolidColor(Color(0xFFF5F4EA)),
            defaultRing = Brush.verticalGradient(
                0f to Color(0xFFCEE7FF), 0.3f to Color(0xFF98B8EA), 0.7f to Color(0xFFBCD4F6), 1f to Color(0xFF89ADE4),
            ),
            checkFill = Brush.linearGradient(listOf(Color(0xFFDCDCD7), Color.White)),
            checkPressedFill = Brush.linearGradient(listOf(Color(0xFFB0B0A7), Color(0xFFE3E1DA))),
            tabFill = Brush.verticalGradient(0f to Color.White, 0.5f to Color(0xFFFAFAF9), 1f to Color(0xFFECEBE5)),
            tabSelected = Color(0xFFFCFCFE),
            dropFill = Brush.verticalGradient(0f to Color(0xFFE6EEFC), 0.5f to Color(0xFFC1D2FA), 1f to Color(0xFFAFC4F4)),
            dropPressedFill = Brush.verticalGradient(0f to Color(0xFF98AEE6), 1f to Color(0xFFB9CAF4)),
            dropBorder = Color(0xFF9DB4E6),
            dropChevron = Color(0xFF4D6185),
            scrollTrack = Brush.horizontalGradient(listOf(Color(0xFFEEEDE5), Color(0xFFFDFDFB))),
            scrollTrackEdge = Color(0xFFE2E0D6),
            scrollThumb = Brush.horizontalGradient(listOf(Color(0xFFCBD9FD), Color(0xFFB6C8F7))),
            scrollThumbBorder = Color(0xFF8FA9E0),
            scrollGripLight = Color(0xFFEEF4FE),
            scrollGripDark = Color(0xFF7F9DE0),

            titleStops = arrayOf(
                0f to Color(0xFF0055EA), 0.45f to Color(0xFF0050EE), 0.9f to Color(0xFF0066FF), 1f to Color(0xFF0060F8),
            ),
            titleGloss = listOf(Color(0xFF3A9BFF), Color(0xFF0055EA)),
            titleEdge = listOf(Color(0xFF0058F0), Color(0xFF003DD7)),
            titleShadow = Color(0xFF0A1883),
            captionStops = arrayOf(
                0f to Color(0xFF0054E9), 0.55f to Color(0xFF2263D5), 0.7f to Color(0xFF4479E4), 0.9f to Color(0xFFA3BBEC), 1f to Color.White,
            ),
            captionPressedStops = arrayOf(0f to Color(0xFF0A3CA8), 0.6f to Color(0xFF1548B8), 1f to Color(0xFF4B77D8)),
            captionEdge = Color.White,

            taskbarStops = arrayOf(0f to Color(0xFF2A6DE2), 0.5f to Color(0xFF245DDB), 1f to Color(0xFF2157D2)),
            taskbarGloss = arrayOf(
                0f to Color(0xFF1F3A9A), 0.2f to Color(0xFF3168D5), 0.5f to Color(0xFF4490E6), 1f to Color(0xFF2A6DE2),
            ),
            taskbarEdge = listOf(Color(0xFF2157D2), Color(0xFF1941A5)),
            taskbarSunken = Color(0xFF1946B8),
            taskbarSunkenBorder = Color(0xFF12348A),
            taskbarGrooveDark = Color(0xFF1941A5),
            taskbarGrooveLight = Color(0xFF5A9BF5),
            trayStops = arrayOf(
                0f to Color(0xFF1AA8F0), 0.12f to Color(0xFF18B5F2), 0.5f to Color(0xFF1290E8), 1f to Color(0xFF0F82DC),
            ),
            trayTopLine = Color(0xFF0E6FCC),
            trayLeftLine = Color(0xFF0B5CB0),
            trayGlint = Color(0xFF5FD0FA),
        )

        val Noir = XpPalette(
            dark = true,

            face = Color(0xFF2D2F34),
            faceLight = Color(0xFF26282C),
            window = Color(0xFF1D1E21),
            menu = Color(0xFF303237),
            tooltip = Color(0xFF3A3824),

            text = Color(0xFFE9E7E0),
            textDim = Color(0xFFA8A69C),
            textDisabled = Color(0xFF6A6962),
            tooltipText = Color(0xFFF4F1D6),
            errorText = Color(0xFFFF8370),
            link = Color(0xFF78AEFF),
            groupText = Color(0xFF86B4FF),
            headerText = Color(0xFFA3C2FA),
            tagText = Color(0xFFFFD88A),

            shadow = Color(0xFF151618),
            etchLight = Color(0xFF3D4046),
            divider = Color(0xFF2D2F34),
            fieldBorder = Color(0xFF5B6B82),
            buttonBorder = Color(0xFF0E0F11),
            disabledBorder = Color(0xFF44464C),
            groupBorder = Color(0xFF484B52),
            tabBorder = Color(0xFF55585F),
            menuBorder = Color(0xFF5C5F68),
            tooltipBorder = Color(0xFF8C875C),
            headerRule = Color(0xFF5878C4),
            tagBorder = Color(0xFFB88C32),
            swatchEdge = Color.White.copy(alpha = 0.4f),
            windowFrame = Color(0xFF3F4758),

            highlight = Color(0xFF316AC5),
            today = Color(0xFFFF5C40),
            tabAccent = Color(0xFFE68B2C),
            tabAccentLight = Color(0xFFFFC73C),
            tagFill = Color(0xFF4B3B14),
            checkBorder = Color(0xFF8094B4),
            checkGreen = Color(0xFF4CC552),
            checkGlint = Color(0xFFB4F09A),
            estimateWarn = Color(0xFFB88C32),
            estimateCritical = Color(0xFFFF5C40),

            buttonFill = Brush.verticalGradient(0f to Color(0xFF585C66), 0.86f to Color(0xFF44474F), 1f to Color(0xFF35373D)),
            buttonPressedFill = Brush.verticalGradient(
                0f to Color(0xFF2A2C31), 0.08f to Color(0xFF34373D), 0.94f to Color(0xFF3A3D44), 1f to Color(0xFF454850),
            ),
            buttonDisabledFill = SolidColor(Color(0xFF313338)),
            defaultRing = Brush.verticalGradient(
                0f to Color(0xFF6FA0E8), 0.3f to Color(0xFF4878C8), 0.7f to Color(0xFF5F8FDA), 1f to Color(0xFF3F6DB8),
            ),
            checkFill = Brush.linearGradient(listOf(Color(0xFF17181B), Color(0xFF2F3136))),
            checkPressedFill = Brush.linearGradient(listOf(Color(0xFF0E0F11), Color(0xFF222428))),
            tabFill = Brush.verticalGradient(0f to Color(0xFF3D4047), 0.5f to Color(0xFF34363C), 1f to Color(0xFF2B2D31)),
            tabSelected = Color(0xFF1D1E21),
            dropFill = Brush.verticalGradient(0f to Color(0xFF52658F), 0.5f to Color(0xFF40527A), 1f to Color(0xFF36466B)),
            dropPressedFill = Brush.verticalGradient(0f to Color(0xFF2C3A5C), 1f to Color(0xFF384A72)),
            dropBorder = Color(0xFF7388B8),
            dropChevron = Color(0xFFE3EAFA),
            scrollTrack = Brush.horizontalGradient(listOf(Color(0xFF222327), Color(0xFF2A2C30))),
            scrollTrackEdge = Color(0xFF141517),
            scrollThumb = Brush.horizontalGradient(listOf(Color(0xFF52658F), Color(0xFF40527A))),
            scrollThumbBorder = Color(0xFF7388B8),
            scrollGripLight = Color(0xFF9AAEDA),
            scrollGripDark = Color(0xFF25314D),

            titleStops = arrayOf(
                0f to Color(0xFF2F3644), 0.45f to Color(0xFF232833), 0.9f to Color(0xFF2C3340), 1f to Color(0xFF272D39),
            ),
            titleGloss = listOf(Color(0xFF6C7890), Color(0xFF2F3644)),
            titleEdge = listOf(Color(0xFF262C37), Color(0xFF0D0F13)),
            titleShadow = Color(0xFF000000),
            captionStops = arrayOf(
                0f to Color(0xFF333B4A), 0.55f to Color(0xFF414B5E), 0.7f to Color(0xFF56617A), 0.9f to Color(0xFFA5AFC4), 1f to Color(0xFFE8ECF4),
            ),
            captionPressedStops = arrayOf(0f to Color(0xFF171B23), 0.6f to Color(0xFF232936), 1f to Color(0xFF48536A)),
            captionEdge = Color(0xFFC9D0DE),

            taskbarStops = arrayOf(0f to Color(0xFF2C313D), 0.5f to Color(0xFF252A34), 1f to Color(0xFF20242D)),
            taskbarGloss = arrayOf(
                0f to Color(0xFF101319), 0.2f to Color(0xFF2F3542), 0.5f to Color(0xFF55607A), 1f to Color(0xFF2C313D),
            ),
            taskbarEdge = listOf(Color(0xFF20242D), Color(0xFF0E1014)),
            taskbarSunken = Color(0xFF14171D),
            taskbarSunkenBorder = Color(0xFF07080A),
            taskbarGrooveDark = Color(0xFF0B0C0F),
            taskbarGrooveLight = Color(0xFF4B5366),
            trayStops = arrayOf(
                0f to Color(0xFF3C4658), 0.12f to Color(0xFF445066), 0.5f to Color(0xFF343D4E), 1f to Color(0xFF2C3443),
            ),
            trayTopLine = Color(0xFF1D232E),
            trayLeftLine = Color(0xFF11151C),
            trayGlint = Color(0xFF6C7A93),
        )
    }
}

/**
 * Which palette is in use. It is snapshot state, so every control that reads a color recomposes or
 * redraws when it changes. Set it from event handlers or before the first frame, not while composing.
 */
object XpAppearance {
    var palette: XpPalette by mutableStateOf(XpPalette.Luna)
        private set

    var dark: Boolean
        get() = palette.dark
        set(value) {
            palette = if (value) XpPalette.Noir else XpPalette.Luna
        }
}

/** The palette in use. Read colors at the point of use; do not keep them in `remember` or statics. */
val XpColors: XpPalette get() = XpAppearance.palette

/** Text styles. They carry no ink: text takes the palette's color from [XpTheme] unless one is passed. */
object XpType {
    private val base = TextStyle(fontFamily = FontFamily.SansSerif)
    val Body = base.copy(fontSize = 15.sp, lineHeight = 20.sp)
    val BodyBold = Body.copy(fontWeight = FontWeight.Bold)
    val Small = base.copy(fontSize = 13.sp, lineHeight = 17.sp)
    val SmallBold = Small.copy(fontWeight = FontWeight.Bold)
    val Tiny = base.copy(fontSize = 12.sp, lineHeight = 15.sp)
    val RowTitle = base.copy(fontSize = 15.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)

    /** Window caption: white in every palette; the title bar adds the palette's shadow. */
    val Caption = base.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
    val Start = base.copy(
        fontSize = 18.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Bold,
        fontStyle = FontStyle.Italic,
        color = Color.White,
        shadow = Shadow(Color(0xFF1B4D1B), Offset(2f, 2f), 2f),
    )
}

/**
 * Wraps content in the XP look. Material components that are still used underneath (swipe, dialog
 * windows) inherit matching colors; ripples are never used, controls draw their own pressed state.
 */
@Composable
fun XpTheme(content: @Composable () -> Unit) {
    val colors = XpColors
    val scheme = (if (colors.dark) darkColorScheme() else lightColorScheme()).copy(
        primary = colors.highlight,
        onPrimary = Color.White,
        secondary = colors.highlight,
        onSecondary = Color.White,
        background = colors.face,
        onBackground = colors.text,
        surface = colors.face,
        onSurface = colors.text,
        surfaceVariant = colors.faceLight,
        onSurfaceVariant = colors.textDim,
        outline = colors.shadow,
        error = colors.errorText,
        onError = Color.White,
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalTextStyle provides XpType.Body,
            LocalContentColor provides colors.text,
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = colors.highlight,
                backgroundColor = colors.highlight.copy(alpha = 0.35f),
            ),
            content = content,
        )
    }
}
