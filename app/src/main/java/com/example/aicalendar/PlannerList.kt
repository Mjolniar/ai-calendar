package com.example.aicalendar

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.example.aicalendar.xp.XpButton
import com.example.aicalendar.xp.XpCheckMark
import com.example.aicalendar.xp.XpColors
import com.example.aicalendar.xp.XpIcons
import com.example.aicalendar.xp.XpLink
import com.example.aicalendar.xp.XpSwatch
import com.example.aicalendar.xp.XpType
import com.example.aicalendar.xp.xpClickable
import java.time.LocalDate

// Class colors tuned for a white list (the stored key picks the slot, so a class keeps its color).
private val coursePalette = longArrayOf(
    0xFF2F7FD9,
    0xFFD08A00,
    0xFF2E9E5B,
    0xFF8757D6,
    0xFFD4558C,
    0xFF1E9C8E,
    0xFF4F6FD8,
    0xFF9C8F1C,
)

internal fun courseColorForKey(key: String): Color {
    val hash = key.fold(0) { value, character -> (value * 31) + character.code }
    return Color(coursePalette[Math.floorMod(hash, coursePalette.size)])
}

/** Color that identifies a reminder at a glance: its class for Canvas work, otherwise its category. */
internal fun PlannerEvent.accentColor(): Color =
    if (isCanvas()) courseColorForKey(courseKey()) else Color(resolvedCategory().colorArgb)

/** Bold blue caption with a fading rule, like a file list shown in groups. */
@Composable
internal fun GroupHeaderRow(label: String, count: Int) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(XpColors.window)
            .padding(start = 12.dp, end = 20.dp, top = 12.dp, bottom = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, style = XpType.BodyBold.copy(fontSize = 14.sp), color = XpColors.headerText)
            Spacer(Modifier.width(6.dp))
            Text("($count)", style = XpType.Small, color = XpColors.textDim)
        }
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Brush.horizontalGradient(listOf(XpColors.headerRule, XpColors.headerRule.copy(alpha = 0f)))),
        )
    }
}

@Composable
internal fun AssessmentTag(kind: AssessmentKind) {
    Row(
        Modifier
            .background(XpColors.tagFill)
            .border(1.dp, XpColors.tagBorder)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(XpIcons.Warning, contentDescription = null, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(3.dp))
        Text(kind.label.uppercase(), style = XpType.Tiny.copy(fontWeight = FontWeight.Bold), color = XpColors.tagText)
    }
}

/**
 * One reminder. Collapsed it is a compact two-line row; tapping selects it (blue highlight) and
 * opens the details with labeled action buttons underneath.
 */
@Composable
internal fun EventRow(
    event: PlannerEvent,
    nowMillis: Long,
    today: LocalDate,
    selected: Boolean,
    showDate: Boolean,
    archivedView: Boolean,
    onSelect: () -> Unit,
    onToggleDone: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onArchive: (() -> Unit)?,
    onRestore: (() -> Unit)?,
    repeatText: String? = null,
) {
    val category = event.resolvedCategory()
    val canvas = event.isCanvas()
    val assessment = event.assessmentKind()
    val accent = event.accentColor()
    val ink = if (selected) Color.White else XpColors.text
    val dim = if (selected) Color.White.copy(alpha = 0.88f) else XpColors.textDim
    val date = eventDate(event)
    val time = formatTime(event.startAtMillis)
    val primaryWhen: String
    val secondaryWhen: String
    when {
        archivedView -> {
            primaryWhen = formatShortDate(date, today)
            secondaryWhen = time
        }
        !showDate || date == today -> {
            primaryWhen = time
            secondaryWhen = when {
                date != today -> ""
                // A delivery or a birthday stays listed all day; "3 h ago" would read as if it had been missed.
                event.startAtMillis < nowMillis && event.isListedAllDay() -> ""
                else -> relativeDue(event.startAtMillis, nowMillis)
            }
        }
        else -> {
            primaryWhen = formatShortDate(date, today)
            secondaryWhen = time
        }
    }
    val secondaryLine = buildString {
        append(if (canvas) event.schoolItemType.displaySchoolItemType() else category.displayName)
        if (event.location.isNotBlank()) append(" - ").append(event.location)
    }

    Column(Modifier.fillMaxWidth().background(XpColors.window)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected) XpColors.highlight else XpColors.window)
                .drawBehind { drawRect(accent, size = Size(5.dp.toPx(), size.height)) }
                .xpClickable(onClickLabel = if (selected) "Hide details" else "Show details", onClick = onSelect)
                .semantics {
                    if (onArchive != null) {
                        customActions = listOf(
                            CustomAccessibilityAction("Archive") {
                                onArchive()
                                true
                            },
                        )
                    }
                }
                .heightIn(min = 58.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .padding(start = 5.dp)
                    .size(width = 46.dp, height = 52.dp)
                    .semantics { contentDescription = if (event.done) "Mark not done" else "Mark done" }
                    .xpClickable(role = Role.Checkbox, onClick = onToggleDone),
                contentAlignment = Alignment.Center,
            ) {
                XpCheckMark(checked = event.done, box = 22.dp)
            }
            Column(Modifier.weight(1f).padding(vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (assessment != null || canvas) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (assessment != null) AssessmentTag(assessment)
                        if (canvas) {
                            Text(
                                compactCourseLabel(event.courseDisplayName()),
                                style = XpType.Tiny.copy(fontWeight = FontWeight.Bold),
                                color = dim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Text(
                    event.title,
                    style = XpType.RowTitle,
                    color = if (event.done && !selected) XpColors.textDim else ink,
                    maxLines = if (selected) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (event.done) TextDecoration.LineThrough else null,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    XpSwatch(accent, box = 10.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(secondaryLine, style = XpType.Small, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(
                // The end padding leaves the strip under the scroll bar free of text.
                Modifier.padding(start = 8.dp, end = 20.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (event.isOccurrence) {
                        Image(XpIcons.Repeat, contentDescription = "Repeats", modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    if (event.critical) {
                        Image(XpIcons.Warning, contentDescription = "Critical", modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(primaryWhen, style = XpType.Small.copy(fontWeight = FontWeight.SemiBold), color = ink, maxLines = 1)
                }
                if (secondaryWhen.isNotEmpty()) {
                    Text(secondaryWhen, style = XpType.Tiny, color = dim, maxLines = 1)
                }
            }
        }
        if (selected) {
            EventDetails(
                event = event,
                nowMillis = nowMillis,
                accent = accent,
                category = category,
                onToggleDone = onToggleDone,
                onEdit = onEdit,
                onDelete = onDelete,
                onArchive = onArchive,
                onRestore = onRestore,
                repeatText = repeatText,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(XpColors.divider))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventDetails(
    event: PlannerEvent,
    nowMillis: Long,
    accent: Color,
    category: EventCategory,
    onToggleDone: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onArchive: (() -> Unit)?,
    onRestore: (() -> Unit)?,
    repeatText: String?,
) {
    val context = LocalContext.current
    val canvas = event.isCanvas()
    val delivery = event.isDelivery()
    val upcoming = !event.done && event.startAtMillis >= nowMillis
    val alerts = when {
        delivery -> "None. Deliveries never alert."
        event.reminderOffsetsMinutes.isEmpty() -> "None"
        else -> formatReminderOffsets(event.reminderOffsetsMinutes).replaceFirstChar { it.uppercase() } +
            if (event.critical) " (exact alarm)" else ""
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(XpColors.faceLight)
            .drawBehind { drawRect(accent, size = Size(5.dp.toPx(), size.height)) }
            .padding(start = 17.dp, end = 20.dp, top = 9.dp, bottom = 11.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        DetailLine(
            "When",
            formatFullDateTime(event.startAtMillis) +
                if (upcoming) " (${relativeDue(event.startAtMillis, nowMillis)})" else "",
        )
        if (event.location.isNotBlank()) DetailLine("Where", event.location)
        if (event.isOccurrence) {
            DetailLine("Repeats", (repeatText ?: "Yes") + if (event.detached) ". This one was changed on its own." else "")
        }
        if (canvas) {
            val course = listOf(
                event.courseName.ifBlank { compactCourseLabel(event.courseDisplayName()) },
                event.schoolItemType.displaySchoolItemType(),
            ).filter(String::isNotBlank).joinToString(" - ")
            DetailLine("Class", course)
            event.assignmentSummary().takeIf(String::isNotBlank)?.let { DetailLine("Work", it) }
            event.unlockAt.takeIf(String::isNotBlank)?.let { DetailLine("Opens", formatCanvasDate(it)) }
            event.lockAt.takeIf(String::isNotBlank)?.let { DetailLine("Locks", formatCanvasDate(it)) }
        }
        DetailLine("Type", "${category.displayName} - ${event.sourceLabel()}")
        DetailLine("Alerts", alerts)
        if (upcoming && !delivery) {
            ReminderSchedule.movedNote(event.startAtMillis, event.reminderOffsetsMinutes)?.let { DetailLine("", it) }
        }
        val body = if (canvas && event.details.isNotBlank()) event.details else event.notes
        if (body.isNotBlank()) {
            SelectionContainer {
                Text(body, style = XpType.Small, modifier = Modifier.padding(top = 3.dp))
            }
        }
        FlowRow(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            XpButton(if (event.done) "Not done" else "Done", onToggleDone, icon = XpIcons.Check)
            XpButton("Edit", onEdit, icon = XpIcons.Edit)
            if (onArchive != null) XpButton("Archive", onArchive, icon = XpIcons.Archive)
            if (onRestore != null) XpButton("Restore", onRestore, icon = XpIcons.Restore)
            XpButton("Delete", onDelete, icon = XpIcons.Delete)
            if (canvas && event.sourceUrl.startsWith("https://")) {
                XpButton(
                    "Open in Canvas",
                    onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, event.sourceUrl.toUri())) }
                    },
                    icon = XpIcons.OpenLink,
                )
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row {
        Text(label, modifier = Modifier.width(54.dp), style = XpType.Small, color = XpColors.textDim)
        Text(value, modifier = Modifier.weight(1f), style = XpType.Small)
    }
}

/** Swipe a row left to archive it; the balloon-colored strip underneath says what will happen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeToArchive(onArchive: () -> Unit, content: @Composable () -> Unit) {
    val swipeState = rememberSwipeToDismissBoxState()
    LaunchedEffect(swipeState.currentValue) {
        if (swipeState.currentValue == SwipeToDismissBoxValue.EndToStart) onArchive()
    }
    SwipeToDismissBox(
        state = swipeState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            // Decoration only: screen readers get an "Archive" action on the row itself instead.
            Row(
                Modifier.fillMaxSize().background(XpColors.tooltip).padding(end = 18.dp).clearAndSetSemantics { },
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(XpIcons.Archive, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Archive", style = XpType.BodyBold, color = XpColors.tooltipText)
            }
        },
    ) {
        content()
    }
}

@Composable
internal fun EmptyListMessage(title: String, actions: List<Pair<String, () -> Unit>> = emptyList()) {
    Column(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(XpIcons.App, contentDescription = null, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, style = XpType.Body, color = XpColors.textDim, textAlign = TextAlign.Center)
        actions.forEach { (label, action) -> XpLink(label, action) }
    }
}
