package com.example.aicalendar.xp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Clickable without the Material ripple; XP controls draw their own pressed state. */
fun Modifier.xpClickable(
    enabled: Boolean = true,
    role: Role? = null,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = clickable(
    interactionSource = null,
    indication = null,
    enabled = enabled,
    onClickLabel = onClickLabel,
    role = role,
    onClick = onClick,
)

/** Standard push button: gradient face, dark border, optional blue "default" ring. */
@Composable
fun XpButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    default: Boolean = false,
    icon: ImageVector? = null,
    minWidth: Dp = 78.dp,
    minHeight: Dp = 38.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(3.dp)
    val fill = when {
        !enabled -> XpColors.buttonDisabledFill
        pressed -> XpColors.buttonPressedFill
        else -> XpColors.buttonFill
    }
    Row(
        modifier = modifier
            .defaultMinSize(minWidth = minWidth, minHeight = minHeight)
            .clip(shape)
            .background(fill)
            .border(1.dp, if (enabled) XpColors.buttonBorder else XpColors.disabledBorder, shape)
            .drawBehind {
                if (default && enabled && !pressed) {
                    val inset = 2.dp.toPx()
                    drawRoundRect(
                        brush = XpColors.defaultRing,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - inset * 2, size.height - inset * 2),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.size(18.dp), alpha = if (enabled) 1f else 0.4f)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = XpType.Body, color = if (enabled) XpColors.text else XpColors.textDisabled, maxLines = 1)
    }
}

enum class XpArrow { LEFT, RIGHT, UP, DOWN }

/** Solid scroll-arrow triangle centered in the draw area. */
fun DrawScope.drawXpArrow(direction: XpArrow, color: Color, extent: Float = size.minDimension * 0.34f) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val half = extent / 2f
    val depth = extent * 0.56f
    val path = Path().apply {
        when (direction) {
            XpArrow.LEFT -> {
                moveTo(cx - depth / 2f, cy)
                lineTo(cx + depth / 2f, cy - half)
                lineTo(cx + depth / 2f, cy + half)
            }
            XpArrow.RIGHT -> {
                moveTo(cx + depth / 2f, cy)
                lineTo(cx - depth / 2f, cy - half)
                lineTo(cx - depth / 2f, cy + half)
            }
            XpArrow.UP -> {
                moveTo(cx, cy - depth / 2f)
                lineTo(cx - half, cy + depth / 2f)
                lineTo(cx + half, cy + depth / 2f)
            }
            XpArrow.DOWN -> {
                moveTo(cx, cy + depth / 2f)
                lineTo(cx - half, cy - depth / 2f)
                lineTo(cx + half, cy - depth / 2f)
            }
        }
        close()
    }
    drawPath(path, color)
}

/** Push button whose face is drawn (arrows, glyphs) instead of text. */
@Composable
fun XpGlyphButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    width: Dp = 38.dp,
    height: Dp = 38.dp,
    glyph: DrawScope.(ink: Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(3.dp)
    val ink = if (enabled) XpColors.text else XpColors.textDisabled
    val fill = when {
        !enabled -> XpColors.buttonDisabledFill
        pressed -> XpColors.buttonPressedFill
        else -> XpColors.buttonFill
    }
    Canvas(
        modifier
            .size(width, height)
            .clip(shape)
            .background(fill)
            .border(1.dp, if (enabled) XpColors.buttonBorder else XpColors.disabledBorder, shape)
            .semantics { contentDescription = label }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
    ) { glyph(ink) }
}

/** Small "x" used to dismiss bars, balloons and the find field. */
@Composable
fun XpCloseGlyph(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, ink: Color = XpColors.textDim) {
    Canvas(
        modifier
            .size(40.dp, 36.dp)
            .semantics { contentDescription = label }
            .xpClickable(role = Role.Button, onClick = onClick),
    ) {
        val half = 4.5.dp.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val stroke = 1.8.dp.toPx()
        drawLine(ink, Offset(cx - half, cy - half), Offset(cx + half, cy + half), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(ink, Offset(cx + half, cy - half), Offset(cx - half, cy + half), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

/** The box part of a check box: shaded square, blue border, green tick. */
@Composable
fun XpCheckMark(
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pressed: Boolean = false,
    box: Dp = 20.dp,
) {
    Canvas(modifier.size(box)) {
        val stroke = 1.dp.toPx()
        drawRect(if (pressed) XpColors.checkPressedFill else XpColors.checkFill)
        drawRect(
            color = if (enabled) XpColors.checkBorder else XpColors.disabledBorder,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(stroke),
        )
        if (checked) {
            val s = size.minDimension
            val tick = Path().apply {
                moveTo(s * 0.22f, s * 0.52f)
                lineTo(s * 0.42f, s * 0.72f)
                lineTo(s * 0.79f, s * 0.27f)
            }
            drawPath(
                tick,
                if (enabled) XpColors.checkGreen else XpColors.textDisabled,
                style = Stroke(width = s * 0.15f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

@Composable
fun XpCheckBox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .heightIn(min = 40.dp)
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        XpCheckMark(checked, enabled = enabled, pressed = pressed)
        Spacer(Modifier.width(9.dp))
        Text(label, style = XpType.Body, color = if (enabled) XpColors.text else XpColors.textDisabled)
    }
}

@Composable
fun XpRadioButton(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .heightIn(min = 40.dp)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val r = size.minDimension / 2f
            drawCircle(if (pressed) XpColors.checkPressedFill else XpColors.checkFill, radius = r)
            drawCircle(
                if (enabled) XpColors.checkBorder else XpColors.disabledBorder,
                radius = r - 0.5.dp.toPx(),
                style = Stroke(1.dp.toPx()),
            )
            if (selected) {
                drawCircle(
                    Brush.radialGradient(
                        listOf(XpColors.checkGlint, XpColors.checkGreen),
                        center = Offset(center.x - r * 0.12f, center.y - r * 0.12f),
                        radius = r * 0.5f,
                    ),
                    radius = r * 0.42f,
                )
            }
        }
        Spacer(Modifier.width(9.dp))
        Text(label, style = XpType.Body, color = if (enabled) XpColors.text else XpColors.textDisabled)
    }
}

/** Flat edit box with the pale-blue XP field border. */
@Composable
fun XpTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    textStyle: TextStyle = XpType.Body,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    focusRequester: FocusRequester? = null,
) {
    // A bare text field does not inherit the theme's ink, so the style has to carry it.
    val inkedStyle = if (textStyle.color.isSpecified) textStyle else textStyle.copy(color = XpColors.text)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = if (focusRequester != null) modifier.focusRequester(focusRequester) else modifier,
        textStyle = inkedStyle,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        cursorBrush = SolidColor(XpColors.text),
        decorationBox = { innerTextField -> XpFieldFrame(value.isEmpty(), placeholder, textStyle, singleLine, innerTextField) },
    )
}

/**
 * The same edit box for a [TextFieldValue], for a caller that sets the text and the cursor
 * (a prefilled box with the cursor at the end).
 */
@Composable
fun XpTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    textStyle: TextStyle = XpType.Body,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    focusRequester: FocusRequester? = null,
) {
    val inkedStyle = if (textStyle.color.isSpecified) textStyle else textStyle.copy(color = XpColors.text)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = if (focusRequester != null) modifier.focusRequester(focusRequester) else modifier,
        textStyle = inkedStyle,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        cursorBrush = SolidColor(XpColors.text),
        decorationBox = { innerTextField -> XpFieldFrame(value.text.isEmpty(), placeholder, textStyle, singleLine, innerTextField) },
    )
}

/** White field with the pale-blue XP border, and the grey placeholder while it is empty. */
@Composable
private fun XpFieldFrame(
    empty: Boolean,
    placeholder: String,
    textStyle: TextStyle,
    singleLine: Boolean,
    innerTextField: @Composable () -> Unit,
) {
    Box(
        Modifier
            .background(XpColors.window)
            .border(1.dp, XpColors.fieldBorder)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
    ) {
        if (empty && placeholder.isNotEmpty()) {
            Text(placeholder, style = textStyle, color = XpColors.textDisabled, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        innerTextField()
    }
}

/** Drop-down list field. Give it a width (fillMaxWidth / weight); the arrow button sits at the end. */
@Composable
fun XpComboBox(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .heightIn(min = 38.dp)
            .background(XpColors.window)
            .border(1.dp, XpColors.fieldBorder)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.DropdownList, onClick = onClick)
            .padding(start = 8.dp, end = 3.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = XpType.Body,
            color = if (enabled) XpColors.text else XpColors.textDisabled,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Canvas(Modifier.padding(start = 6.dp).size(width = 28.dp, height = 30.dp)) {
            val radius = CornerRadius(2.dp.toPx())
            val stroke = 1.dp.toPx()
            drawRoundRect(if (pressed) XpColors.dropPressedFill else XpColors.dropFill, cornerRadius = radius)
            drawRoundRect(
                color = XpColors.dropBorder,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = radius,
                style = Stroke(stroke),
            )
            val cx = size.width / 2f
            val cy = size.height / 2f
            val half = size.width * 0.19f
            val chevron = Path().apply {
                moveTo(cx - half, cy - half * 0.5f)
                lineTo(cx, cy + half * 0.5f)
                lineTo(cx + half, cy - half * 0.5f)
            }
            drawPath(chevron, XpColors.dropChevron, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Rounded frame with a blue caption sitting on the top border. Meant for surfaces in the face color. */
@Composable
fun XpGroupBox(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier) {
        Column(
            Modifier
                .padding(top = 9.dp)
                .fillMaxWidth()
                .border(1.dp, XpColors.groupBorder, RoundedCornerShape(4.dp))
                .padding(start = 10.dp, end = 10.dp, top = 13.dp, bottom = 7.dp),
            content = content,
        )
        Text(
            title,
            modifier = Modifier.padding(start = 9.dp).background(XpColors.face).padding(horizontal = 4.dp),
            style = XpType.Small,
            color = XpColors.groupText,
        )
    }
}

/** Information bar in the balloon color; highlights like a link while pressed. */
@Composable
fun XpInfoBar(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val active = pressed && onClick != null
    val ink = if (active) Color.White else XpColors.tooltipText
    Row(
        modifier
            .fillMaxWidth()
            .background(if (active) XpColors.highlight else XpColors.tooltip)
            .drawBehind {
                val line = 1.dp.toPx()
                drawRect(XpColors.shadow, topLeft = Offset(0f, size.height - line), size = Size(size.width, line))
            }
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .heightIn(min = 40.dp)
            .padding(start = 10.dp, top = 5.dp, bottom = 5.dp, end = if (onDismiss != null) 0.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, modifier = Modifier.weight(1f), style = XpType.Small, color = ink)
        if (onDismiss != null) XpCloseGlyph("Dismiss", onDismiss, ink = if (active) Color.White else XpColors.textDim)
    }
}

@Composable
fun XpLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, style: TextStyle = XpType.Body) {
    Text(
        text,
        modifier = modifier.xpClickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp),
        style = style,
        color = XpColors.link,
        textDecoration = TextDecoration.Underline,
    )
}

/** Small color chip (category / class color) with an edge so it stays visible on any background. */
@Composable
fun XpSwatch(color: Color, modifier: Modifier = Modifier, box: Dp = 11.dp) {
    Box(modifier.size(box).background(color).border(1.dp, XpColors.swatchEdge))
}

/** Etched separator: dark line over a light line. */
@Composable
fun XpEtchedLine(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(XpColors.shadow))
        Box(Modifier.fillMaxWidth().height(1.dp).background(XpColors.etchLight))
    }
}

private val TabStripInset = 6.dp
private val TabPadding = 12.dp
private val TabPaddingTight = 6.dp

/**
 * Tab control header. The selected tab is taller, list-colored, and carries the orange top stripe.
 *
 * Each tab is its label plus side padding. A crowded strip tightens that padding so every tab stays
 * on screen; only when that is not enough (large text) does the strip scroll sideways.
 */
@Composable
fun XpTabStrip(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .background(XpColors.face)
            .drawBehind {
                val line = 1.dp.toPx()
                drawRect(XpColors.tabBorder, topLeft = Offset(0f, size.height - line), size = Size(size.width, line))
            },
    ) {
        val viewport = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        // When the strip does have to scroll, the selected tab is kept on screen.
        val reveal = remember(tabs.size) { List(tabs.size) { BringIntoViewRequester() } }
        LaunchedEffect(selectedIndex, tabs.size) { reveal.getOrNull(selectedIndex)?.bringIntoView() }
        Layout(
            content = {
                tabs.forEachIndexed { index, label ->
                    XpTab(
                        label,
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                        modifier = Modifier.bringIntoViewRequester(reveal[index]),
                    )
                }
            },
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = TabStripInset, end = TabStripInset, top = TabStripInset),
        ) { measurables, _ ->
            val labels = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
            val roomy = TabPadding.roundToPx()
            val padding = if (viewport == Constraints.Infinity || labels.isEmpty()) {
                roomy
            } else {
                val spare = viewport - TabStripInset.roundToPx() * 2 - labels.sum()
                (spare / (labels.size * 2)).coerceIn(TabPaddingTight.roundToPx(), roomy)
            }
            val placed = measurables.mapIndexed { index, tab -> tab.measure(Constraints.fixedWidth(labels[index] + padding * 2)) }
            val height = placed.maxOfOrNull { it.height } ?: 0
            layout(placed.sumOf { it.width }, height) {
                var x = 0
                placed.forEach { tab ->
                    // Bottom-aligned, so the taller selected tab rises above its neighbors.
                    tab.placeRelative(x, height - tab.height)
                    x += tab.width
                }
            }
        }
    }
}

@Composable
private fun XpTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(top = if (selected) 0.dp else 3.dp)
            .drawBehind {
                val line = 1.dp.toPx()
                val r = 3.dp.toPx()
                val w = size.width
                val h = size.height
                val edge = line / 2f
                val outline = Path().apply {
                    moveTo(edge, h)
                    lineTo(edge, r)
                    quadraticTo(edge, edge, r, edge)
                    lineTo(w - r, edge)
                    quadraticTo(w - edge, edge, w - edge, r)
                    lineTo(w - edge, h)
                }
                val fill = Path().apply {
                    addPath(outline)
                    close()
                }
                if (selected) {
                    drawPath(fill, XpColors.tabSelected)
                    clipPath(fill) {
                        drawRect(XpColors.tabAccent, size = Size(w, 1.2.dp.toPx()))
                        drawRect(XpColors.tabAccentLight, topLeft = Offset(0f, 1.2.dp.toPx()), size = Size(w, 2.dp.toPx()))
                    }
                    drawLine(XpColors.tabBorder, Offset(edge, r), Offset(edge, h), strokeWidth = line)
                    drawLine(XpColors.tabBorder, Offset(w - edge, r), Offset(w - edge, h), strokeWidth = line)
                } else {
                    drawPath(fill, XpColors.tabFill)
                    drawPath(outline, XpColors.tabBorder, style = Stroke(line))
                    drawRect(XpColors.tabBorder, topLeft = Offset(0f, h - line), size = Size(w, line))
                }
            }
            .xpClickable(role = Role.Tab, onClick = onClick)
            // The strip sets the width (label plus side padding); see XpTabStrip.
            .padding(vertical = if (selected) 9.dp else 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = XpType.Body.copy(fontSize = 14.sp), maxLines = 1)
    }
}

/**
 * Scroll bar for a lazy list: flat track, blue thumb with grip lines. Drag the thumb to scroll
 * quickly; tap the track to move a page. Lay it over the list's right edge: while the list fits on
 * screen it draws nothing and lets touches through.
 */
@Composable
fun XpVerticalScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val metrics by remember(state) { derivedStateOf { scrollMetrics(state) } }
    val current = metrics ?: return
    Canvas(
        modifier
            .fillMaxHeight()
            .width(15.dp)
            .pointerInput(state) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    val now = scrollMetrics(state) ?: return@detectVerticalDragGestures
                    val travel = size.height * (1f - now.thumbFraction)
                    if (travel > 0f) state.dispatchRawDelta(dragAmount * now.maxScroll / travel)
                }
            }
            .pointerInput(state) {
                detectTapGestures { position ->
                    val now = scrollMetrics(state) ?: return@detectTapGestures
                    val thumbHeight = size.height * now.thumbFraction
                    val thumbTop = (size.height - thumbHeight) * now.position
                    val page = now.viewport * 0.9f
                    if (position.y < thumbTop) state.dispatchRawDelta(-page)
                    if (position.y > thumbTop + thumbHeight) state.dispatchRawDelta(page)
                }
            },
    ) {
        val line = 1.dp.toPx()
        drawRect(XpColors.scrollTrack)
        drawRect(XpColors.scrollTrackEdge, size = Size(line, size.height))
        val inset = 2.dp.toPx()
        val thumbHeight = size.height * current.thumbFraction
        val thumbTop = (size.height - thumbHeight) * current.position
        val thumbSize = Size(size.width - inset * 2, thumbHeight)
        val radius = CornerRadius(3.dp.toPx())
        drawRoundRect(
            XpColors.scrollThumb,
            topLeft = Offset(inset, thumbTop),
            size = thumbSize,
            cornerRadius = radius,
        )
        drawRoundRect(
            XpColors.scrollThumbBorder,
            topLeft = Offset(inset + line / 2f, thumbTop + line / 2f),
            size = Size(thumbSize.width - line, thumbSize.height - line),
            cornerRadius = radius,
            style = Stroke(line),
        )
        if (thumbHeight > 26.dp.toPx()) {
            val gripLeft = inset + 3.dp.toPx()
            val gripRight = size.width - inset - 3.dp.toPx()
            val middle = thumbTop + thumbHeight / 2f
            for (step in -1..1) {
                val y = middle + step * 3.dp.toPx()
                drawLine(XpColors.scrollGripLight, Offset(gripLeft, y - line), Offset(gripRight, y - line), strokeWidth = line)
                drawLine(XpColors.scrollGripDark, Offset(gripLeft, y), Offset(gripRight, y), strokeWidth = line)
            }
        }
    }
}

private class XpScrollMetrics(val position: Float, val thumbFraction: Float, val maxScroll: Float, val viewport: Float)

/** Estimates the scroll position from the visible rows (rows differ in height, so it is approximate). */
private fun scrollMetrics(state: LazyListState): XpScrollMetrics? {
    if (!state.canScrollForward && !state.canScrollBackward) return null
    val info = state.layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return null
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    val averageSize = visible.sumOf { it.size }.toFloat() / visible.size
    val contentHeight = averageSize * info.totalItemsCount + info.beforeContentPadding + info.afterContentPadding
    val maxScroll = (contentHeight - viewport).coerceAtLeast(1f)
    val scrolled = state.firstVisibleItemIndex * averageSize + state.firstVisibleItemScrollOffset
    val position = when {
        !state.canScrollForward -> 1f
        !state.canScrollBackward -> 0f
        else -> (scrolled / maxScroll).coerceIn(0f, 1f)
    }
    return XpScrollMetrics(position, (viewport / contentHeight).coerceIn(0.1f, 0.95f), maxScroll, viewport)
}
