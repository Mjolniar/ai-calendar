package com.example.aicalendar.xp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

// ------------------------------------------------------------------ window caption

/** Caption gradient with a glossy top edge and a dark bottom line, drawn in absolute bands. */
fun Modifier.xpTitleBackground(): Modifier = drawBehind {
    val colors = XpColors
    val highlight = 3.dp.toPx()
    val edge = 2.dp.toPx()
    drawRect(Brush.verticalGradient(*colors.titleStops))
    drawRect(
        brush = Brush.verticalGradient(colors.titleGloss, startY = 0f, endY = highlight),
        size = Size(size.width, highlight),
    )
    drawRect(
        brush = Brush.verticalGradient(colors.titleEdge, startY = size.height - edge, endY = size.height),
        topLeft = Offset(0f, size.height - edge),
        size = Size(size.width, edge),
    )
}

@Composable
fun XpTitleBar(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onMinimize: (() -> Unit)? = null,
    showMaximize: Boolean = false,
    onClose: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 8.dp, end = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = XpType.Caption.copy(shadow = Shadow(XpColors.titleShadow, Offset(1.5f, 2f), 1f)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onMinimize != null) XpCaptionButton(CaptionGlyph.MINIMIZE, "Minimize", onMinimize)
        if (showMaximize) XpCaptionButton(CaptionGlyph.MAXIMIZE, "Maximize (unavailable)", null)
        if (onClose != null) XpCaptionButton(CaptionGlyph.CLOSE, "Close", onClose)
    }
}

private enum class CaptionGlyph { MINIMIZE, MAXIMIZE, CLOSE }

// Close is red in every palette; Minimize and Maximize take their shading from the palette.
private val captionRed = arrayOf(
    0f to Color(0xFFCC4600), 0.55f to Color(0xFFDC6527), 0.7f to Color(0xFFCD7546), 0.9f to Color(0xFFFFCCB2), 1f to Color.White,
)
private val captionRedPressed = arrayOf(0f to Color(0xFF8C2A00), 0.6f to Color(0xFFB04010), 1f to Color(0xFFD07850))

@Composable
private fun XpCaptionButton(glyph: CaptionGlyph, label: String, onClick: (() -> Unit)?) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val enabled = onClick != null
    Canvas(
        Modifier
            .padding(start = 3.dp)
            .size(28.dp)
            .semantics { contentDescription = label }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick ?: {},
            ),
    ) {
        val w = size.width
        val h = size.height
        val stroke = 1.dp.toPx()
        val radius = CornerRadius(3.5.dp.toPx())
        val stops = when {
            glyph == CaptionGlyph.CLOSE && pressed -> captionRedPressed
            glyph == CaptionGlyph.CLOSE -> captionRed
            pressed -> XpColors.captionPressedStops
            else -> XpColors.captionStops
        }
        drawRoundRect(
            brush = Brush.radialGradient(*stops, center = Offset(w * 0.9f, h * 0.9f), radius = w * 1.25f),
            cornerRadius = radius,
            alpha = if (enabled) 1f else 0.6f,
        )
        drawRoundRect(
            color = XpColors.captionEdge,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(w - stroke, h - stroke),
            cornerRadius = radius,
            style = Stroke(stroke),
            alpha = if (enabled) 0.95f else 0.5f,
        )
        val ink = Color.White.copy(alpha = if (enabled) 1f else 0.45f)
        when (glyph) {
            CaptionGlyph.MINIMIZE -> drawRect(ink, topLeft = Offset(w * 0.24f, h * 0.64f), size = Size(w * 0.34f, h * 0.13f))
            CaptionGlyph.MAXIMIZE -> {
                drawRect(ink, topLeft = Offset(w * 0.27f, h * 0.27f), size = Size(w * 0.46f, h * 0.46f), style = Stroke(1.5.dp.toPx()))
                drawRect(ink, topLeft = Offset(w * 0.27f, h * 0.25f), size = Size(w * 0.46f, h * 0.11f))
            }
            CaptionGlyph.CLOSE -> {
                val line = 2.4.dp.toPx()
                drawLine(ink, Offset(w * 0.31f, h * 0.31f), Offset(w * 0.69f, h * 0.69f), strokeWidth = line, cap = StrokeCap.Round)
                drawLine(ink, Offset(w * 0.69f, h * 0.31f), Offset(w * 0.31f, h * 0.69f), strokeWidth = line, cap = StrokeCap.Round)
            }
        }
    }
}

// ------------------------------------------------------------------ menus

class XpMenu(val title: String, val content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit)

@Composable
fun XpMenuBar(menus: List<XpMenu>, modifier: Modifier = Modifier) {
    var open by remember { mutableIntStateOf(-1) }
    Row(
        modifier.fillMaxWidth().background(XpColors.face).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        menus.forEachIndexed { index, menu ->
            val active = open == index
            Box {
                Text(
                    menu.title,
                    modifier = Modifier
                        .background(if (active) XpColors.highlight else Color.Transparent)
                        .xpClickable(role = Role.Button) { open = if (active) -1 else index }
                        .padding(horizontal = 11.dp, vertical = 9.dp),
                    style = XpType.Body,
                    color = if (active) Color.White else XpColors.text,
                )
                XpPopupMenu(expanded = active, onDismiss = { if (open == index) open = -1 }) {
                    menu.content(this) { open = -1 }
                }
            }
        }
    }
}

/** Drop-down list anchored below its parent, with the hard XP drop shadow. */
@Composable
fun XpPopupMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    minWidth: Dp = 172.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    Popup(
        popupPositionProvider = XpMenuPositionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        XpMenuSurface(minWidth = minWidth, content = content)
    }
}

/** The menu's panel; split out so it can be drawn without a popup window. */
@Composable
fun XpMenuSurface(minWidth: Dp = 172.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .padding(end = 3.dp, bottom = 3.dp)
            .drawBehind {
                val offset = 3.dp.toPx()
                drawRect(Color.Black.copy(alpha = 0.28f), topLeft = Offset(offset, offset), size = size)
            }
            .background(XpColors.menu)
            .border(1.dp, XpColors.menuBorder)
            .padding(2.dp)
            .widthIn(min = minWidth)
            .width(IntrinsicSize.Max)
            .verticalScroll(rememberScrollState()),
        content = content,
    )
}

private object XpMenuPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = if (anchorBounds.left + popupContentSize.width > windowSize.width) {
            (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        } else {
            anchorBounds.left
        }
        val below = anchorBounds.bottom
        val y = if (below + popupContentSize.height <= windowSize.height) {
            below
        } else {
            val above = anchorBounds.top - popupContentSize.height
            if (above >= 0) above else (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        }
        return IntOffset(x, y)
    }
}

@Composable
fun XpMenuItem(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    checked: Boolean = false,
    radio: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ink = when {
        !enabled -> XpColors.textDisabled
        pressed -> Color.White
        else -> XpColors.text
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(if (pressed && enabled) XpColors.highlight else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(end = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(38.dp), contentAlignment = Alignment.Center) {
            when {
                leading != null -> leading()
                checked && radio -> Canvas(Modifier.size(8.dp)) { drawCircle(ink) }
                checked -> Canvas(Modifier.size(14.dp)) {
                    val s = size.minDimension
                    val tick = Path().apply {
                        moveTo(s * 0.12f, s * 0.52f)
                        lineTo(s * 0.4f, s * 0.8f)
                        lineTo(s * 0.9f, s * 0.2f)
                    }
                    drawPath(tick, ink, style = Stroke(width = s * 0.17f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                icon != null -> Image(icon, contentDescription = null, modifier = Modifier.size(20.dp), alpha = if (enabled) 1f else 0.4f)
            }
        }
        Text(text, style = XpType.Body, color = ink, maxLines = 1)
    }
}

@Composable
fun XpMenuSeparator() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 3.dp).heightIn(min = 1.dp).background(XpColors.menuBorder))
}

// ------------------------------------------------------------------ taskbar

/** Taskbar gradient: glossy top band, flat body, darker bottom edge. */
fun Modifier.xpTaskbarBackground(): Modifier = drawBehind {
    val colors = XpColors
    val top = 5.dp.toPx()
    val bottom = 3.dp.toPx()
    drawRect(Brush.verticalGradient(*colors.taskbarStops))
    drawRect(
        brush = Brush.verticalGradient(*colors.taskbarGloss, startY = 0f, endY = top),
        size = Size(size.width, top),
    )
    drawRect(
        brush = Brush.verticalGradient(colors.taskbarEdge, startY = size.height - bottom, endY = size.height),
        topLeft = Offset(0f, size.height - bottom),
        size = Size(size.width, bottom),
    )
}

// The New button is green in every palette, like the Start button it is modeled on.
private val startGreen = Brush.verticalGradient(
    0f to Color(0xFF7BD06A), 0.1f to Color(0xFF55B04E), 0.5f to Color(0xFF3C943C), 0.9f to Color(0xFF2F7E2F), 1f to Color(0xFF256B27),
)
private val startGreenPressed = Brush.verticalGradient(0f to Color(0xFF2B6E2B), 1f to Color(0xFF3F8F3F))

/** The green pill at the left of the taskbar: flush left, rounded right. */
@Composable
fun XpStartButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp)
    Row(
        modifier
            .fillMaxHeight()
            .clip(shape)
            .background(if (pressed) startGreenPressed else startGreen)
            .drawBehind {
                drawRect(Color.White.copy(alpha = 0.32f), size = Size(size.width, 1.5.dp.toPx()))
                drawRect(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.28f)),
                        startX = size.width - 10.dp.toPx(),
                        endX = size.width,
                    ),
                    topLeft = Offset(size.width - 10.dp.toPx(), 0f),
                    size = Size(10.dp.toPx(), size.height),
                )
            }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(start = 10.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = XpType.Start, maxLines = 1)
    }
}

/** Quick-launch style icon on the taskbar; shows a sunken plate while pressed or active. */
@Composable
fun XpTaskbarIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    rotation: Float = 0f,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sunken = (pressed || active) && enabled
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier
            .fillMaxHeight()
            .width(44.dp)
            .padding(horizontal = 2.dp, vertical = 6.dp)
            .clip(shape)
            .background(if (sunken) XpColors.taskbarSunken else Color.Transparent)
            .then(if (sunken) Modifier.border(1.dp, XpColors.taskbarSunkenBorder, shape) else Modifier)
            .semantics { contentDescription = label }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(icon, contentDescription = null, modifier = Modifier.size(24.dp).rotate(rotation), alpha = if (enabled) 1f else 0.4f)
    }
}

/** Etched grip line between taskbar sections. */
@Composable
fun XpTaskbarDivider(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxHeight().width(7.dp)) {
        val line = 1.dp.toPx()
        val inset = 8.dp.toPx()
        val x = size.width / 2f
        drawRect(XpColors.taskbarGrooveDark, topLeft = Offset(x - line, inset), size = Size(line, size.height - inset * 2))
        drawRect(XpColors.taskbarGrooveLight, topLeft = Offset(x, inset), size = Size(line, size.height - inset * 2))
    }
}

/** Notification-area plate at the right end of the taskbar. */
@Composable
fun XpTray(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .fillMaxHeight()
            .drawBehind {
                val colors = XpColors
                val line = 1.dp.toPx()
                drawRect(Brush.verticalGradient(*colors.trayStops))
                drawRect(colors.trayTopLine, size = Size(size.width, line))
                drawRect(colors.trayLeftLine, size = Size(line, size.height))
                drawRect(colors.trayGlint.copy(alpha = 0.6f), topLeft = Offset(line, line), size = Size(line, size.height - line))
            }
            .padding(start = 6.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

// ------------------------------------------------------------------ dialogs

/**
 * When true, [XpDialog] draws its window inside the current layout instead of opening a separate
 * dialog window. Previews and screenshot tests set this; the app never does.
 */
val LocalXpInlineDialogs = staticCompositionLocalOf { false }

/**
 * Modal window with the blue frame and caption. The body scrolls when it does not fit, so the
 * button row always stays reachable (long notes, large text, keyboard open).
 */
@Composable
fun XpDialog(
    title: String,
    onDismiss: () -> Unit,
    dismissOnOutside: Boolean = false,
    buttons: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val frameModifier = Modifier.padding(12.dp)
    if (LocalXpInlineDialogs.current) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = DIALOG_DIM)), contentAlignment = Alignment.Center) {
            XpWindowFrame(title, onDismiss, frameModifier, buttons, content)
        }
        return
    }
    Dialog(
        onDismissRequest = onDismiss,
        // An edge-to-edge dialog window is the only mode in which the keyboard reaches the layout as
        // an inset (the default mode pans the window and hides the buttons behind the keyboard).
        // The content fills that window, so outside taps are handled here, not by the platform.
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(DIALOG_DIM) }
        val dismiss by rememberUpdatedState(onDismiss)
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(dismissOnOutside) { detectTapGestures { if (dismissOnOutside) dismiss() } }
                // Keeps the frame clear of the status bar, the navigation bar and the keyboard.
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center,
        ) {
            // The frame swallows taps on its own blank areas so they do not count as outside taps.
            XpWindowFrame(title, onDismiss, frameModifier.pointerInput(Unit) { detectTapGestures { } }, buttons, content)
        }
    }
}

private const val DIALOG_DIM = 0.4f

/** The dialog's window chrome and layout; split out so it can be drawn without a dialog window. */
@Composable
fun XpWindowFrame(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    buttons: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
    Column(
        modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .shadow(10.dp, shape)
            .clip(shape)
            .background(XpColors.windowFrame),
    ) {
        XpTitleBar(title, Modifier.xpTitleBackground(), onClose = onClose)
        Column(Modifier.padding(start = 3.dp, end = 3.dp, bottom = 3.dp).background(XpColors.face)) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
                content = content,
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                content = buttons,
            )
        }
    }
}

/** Classic message box: icon on the left, text on the right, buttons below. */
@Composable
fun XpMessageBox(
    title: String,
    message: String,
    icon: ImageVector,
    onDismiss: () -> Unit,
    buttons: @Composable RowScope.() -> Unit,
) {
    XpDialog(title = title, onDismiss = onDismiss, dismissOnOutside = true, buttons = buttons) {
        Row(verticalAlignment = Alignment.Top) {
            Image(icon, contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(36.dp))
            Spacer(Modifier.width(12.dp))
            Text(message, style = XpType.Body, modifier = Modifier.weight(1f))
        }
    }
}

// ------------------------------------------------------------------ balloon tip

/** Balloon tip with a tail pointing down; [tailFromEnd] aims the tail at its anchor. */
@Composable
fun XpBalloon(
    title: String,
    text: String,
    icon: ImageVector,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    tailFromEnd: Dp = 26.dp,
) {
    val tail = 12.dp
    Column(
        modifier
            .drawBehind {
                val r = 7.dp.toPx()
                val w = size.width
                val bodyH = size.height - tail.toPx()
                val tipX = w - tailFromEnd.toPx()
                val outline = Path().apply {
                    moveTo(r, 0f)
                    lineTo(w - r, 0f)
                    arcTo(Rect(w - 2 * r, 0f, w, 2 * r), -90f, 90f, false)
                    lineTo(w, bodyH - r)
                    arcTo(Rect(w - 2 * r, bodyH - 2 * r, w, bodyH), 0f, 90f, false)
                    lineTo(tipX + 4.dp.toPx(), bodyH)
                    lineTo(tipX + 6.dp.toPx(), size.height)
                    lineTo(tipX - 12.dp.toPx(), bodyH)
                    lineTo(r, bodyH)
                    arcTo(Rect(0f, bodyH - 2 * r, 2 * r, bodyH), 90f, 90f, false)
                    lineTo(0f, r)
                    arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
                    close()
                }
                translate(2.dp.toPx(), 2.dp.toPx()) { drawPath(outline, Color.Black.copy(alpha = 0.2f)) }
                drawPath(outline, XpColors.tooltip)
                drawPath(outline, XpColors.tooltipBorder, style = Stroke(1.dp.toPx(), join = StrokeJoin.Round))
            }
            .padding(bottom = tail)
            .padding(start = 10.dp, top = 4.dp, end = 2.dp, bottom = 9.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides XpColors.tooltipText) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text(title, modifier = Modifier.weight(1f), style = XpType.SmallBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                XpCloseGlyph("Close", onDismiss)
            }
            if (text.isNotBlank()) {
                Text(
                    text,
                    modifier = Modifier.padding(start = 25.dp, end = 10.dp),
                    style = XpType.Small,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (actionLabel != null && onAction != null) {
                XpLink(actionLabel, onAction, Modifier.padding(start = 25.dp), style = XpType.SmallBold)
            }
        }
    }
}
