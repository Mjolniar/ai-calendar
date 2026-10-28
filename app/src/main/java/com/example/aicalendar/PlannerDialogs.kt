package com.example.aicalendar

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aicalendar.xp.XpArrow
import com.example.aicalendar.xp.XpButton
import com.example.aicalendar.xp.XpCheckBox
import com.example.aicalendar.xp.XpColors
import com.example.aicalendar.xp.XpComboBox
import com.example.aicalendar.xp.XpDialog
import com.example.aicalendar.xp.XpEtchedLine
import com.example.aicalendar.xp.XpGlyphButton
import com.example.aicalendar.xp.XpGroupBox
import com.example.aicalendar.xp.XpIcons
import com.example.aicalendar.xp.XpLink
import com.example.aicalendar.xp.XpMenuItem
import com.example.aicalendar.xp.XpPopupMenu
import com.example.aicalendar.xp.XpRadioButton
import com.example.aicalendar.xp.XpSwatch
import com.example.aicalendar.xp.XpTextField
import com.example.aicalendar.xp.XpType
import com.example.aicalendar.xp.drawXpArrow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private val editorDateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")
private val editorTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
private val pickedDateFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")

/** Lead times always offered in the editor; any other value already on the reminder is listed too. */
private val standardOffsets = listOf(0, 60, 1440, 4320, 10080)

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = XpType.Small)
}

@Composable
internal fun EventEditorDialog(
    initial: PlannerEvent?,
    saveError: String?,
    onDismiss: () -> Unit,
    onSave: (PlannerEvent, RepeatChoice?) -> Unit,
    newDate: LocalDate? = null,
    newDomain: EventDomain = EventDomain.LIFE,
    repeatMode: RepeatEditMode = if (initial == null) RepeatEditMode.NEW else RepeatEditMode.NONE,
    initialRepeat: RepeatChoice? = null,
    canRepeat: Boolean = true,
) {
    val zone = remember { ZoneId.systemDefault() }
    // New reminders start at 9:00 AM tomorrow (or on [newDate], the day open in the calendar view)
    // and go to [newDomain], the tab they were started from. One started on Deliveries is a delivery.
    val start = remember {
        initial?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it.startAtMillis), zone) }
            ?: (newDate ?: LocalDate.now().plusDays(1)).atTime(9, 0)
    }
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var date by remember { mutableStateOf(start.toLocalDate()) }
    var time by remember { mutableStateOf(start.toLocalTime().withSecond(0).withNano(0)) }
    var location by remember { mutableStateOf(initial?.location.orEmpty()) }
    var notes by remember { mutableStateOf(initial?.notes.orEmpty()) }
    var critical by remember { mutableStateOf(initial?.critical ?: false) }
    var category by remember { mutableStateOf(initial?.category ?: EventCategory.AUTO) }
    var domain by remember { mutableStateOf(initial?.resolvedDomain() ?: newDomain) }
    var offsets by remember { mutableStateOf(initial?.reminderOffsetsMinutes ?: defaultReminderOffsets(false)) }
    val delivery = domain == EventDomain.DELIVERY
    val startedAsDelivery = remember { initial?.isDelivery() == true }
    // Repeat: the pattern, or null for "Does not repeat". A delivery never repeats.
    var repeat by remember { mutableStateOf(initialRepeat?.frequency) }
    var intervalText by remember { mutableStateOf((initialRepeat?.interval ?: 1).toString()) }
    var weekdays by remember { mutableStateOf(initialRepeat?.weekdays?.toSet() ?: emptySet()) }
    var repeatEnd by remember { mutableStateOf(initialRepeat?.end ?: RepeatEnd.NEVER) }
    var repeatUntil by remember { mutableStateOf(initialRepeat?.until) }
    var countText by remember { mutableStateOf((initialRepeat?.count ?: 10).toString()) }
    var pickingUntil by remember { mutableStateOf(false) }
    val repeating = repeatMode != RepeatEditMode.NONE && repeat != null && !delivery

    fun chooseDomain(next: EventDomain) {
        if (next == domain) return
        if (next != EventDomain.DELIVERY) {
            // The Delivery category belongs to the Deliveries calendar only.
            if (category == EventCategory.DELIVERY) category = EventCategory.AUTO
            // A saved delivery has no alerts; it gets the usual ones back when it stops being one.
            if (domain == EventDomain.DELIVERY && startedAsDelivery && offsets.isEmpty()) {
                offsets = defaultReminderOffsets(critical)
            }
        }
        domain = next
    }
    var categoryMenu by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val titleFocus = remember { FocusRequester() }

    // A new reminder starts with the cursor in Title, keyboard up; edits open without the keyboard.
    LaunchedEffect(Unit) {
        if (initial == null) {
            delay(200)
            runCatching { titleFocus.requestFocus() }
        }
    }

    // Keep the stored instant (including seconds) when the date and time were not touched.
    val untouched = initial != null &&
        date == start.toLocalDate() &&
        time == start.toLocalTime().withSecond(0).withNano(0)
    val startMillis = if (untouched) {
        initial!!.startAtMillis
    } else {
        LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
    }
    // A delivery and a birthday stay current through their whole day.
    val allDay = delivery || isBirthdayTitle(title)
    val inPast = currentUntilMillis(startMillis, allDay, zone) < System.currentTimeMillis()

    fun repeatChoice(): RepeatChoice? = repeat?.takeIf { repeating }?.let { frequency ->
        RepeatChoice(
            frequency = frequency,
            interval = intervalText.toIntOrNull() ?: 0,
            weekdays = if (frequency == RepeatFrequency.WEEKLY) weekdays.ifEmpty { setOf(date.dayOfWeek.value) }.sorted() else emptyList(),
            end = repeatEnd,
            until = repeatUntil.takeIf { repeatEnd == RepeatEnd.ON_DATE },
            count = countText.toIntOrNull().takeIf { repeatEnd == RepeatEnd.AFTER },
        )
    }

    fun save() {
        val base = initial ?: PlannerEvent(title = "", startAtMillis = 0L, source = "manual")
        val candidate = base.copy(
            title = title.trim(),
            startAtMillis = startMillis,
            location = location.trim(),
            notes = notes.trim(),
            critical = critical,
            category = category,
            domain = domain,
            // A delivery is saved without alerts.
            reminderOffsetsMinutes = if (delivery) emptyList() else offsets.distinct().sortedDescending(),
        )
        val choice = repeatChoice()
        val problem = candidate.validationError()
            ?: choice?.validationError(date)
            ?: if (choice != null && !canRepeat) "A repeating reminder is kept in the cloud. Connect this phone to the cloud first." else null
        if (problem != null) {
            error = problem
        } else {
            error = null
            onSave(candidate, choice)
        }
    }

    XpDialog(
        title = (if (initial == null) "New " else "Edit ") + (if (delivery) "Delivery" else "Reminder"),
        onDismiss = onDismiss,
        buttons = {
            XpButton("Save", onClick = ::save, default = true)
            XpButton("Cancel", onClick = onDismiss)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            FieldLabel("Title")
            XpTextField(
                title,
                { title = it },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                focusRequester = titleFocus,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1.5f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                FieldLabel(if (delivery) "Expected" else "Date")
                XpComboBox(date.format(editorDateFormatter), { pickingDate = true }, Modifier.fillMaxWidth())
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                FieldLabel("Time")
                XpComboBox(time.format(editorTimeFormatter), { pickingTime = true }, Modifier.fillMaxWidth())
            }
        }
        if (repeatMode == RepeatEditMode.SERIES) {
            Text("Changes apply to this and all future occurrences.", style = XpType.Small, color = XpColors.textDim)
        }
        if (inPast && !repeating && !(initial?.done ?: false)) {
            Text(
                when {
                    delivery -> "That day has passed, so this delivery will be filed under Archived."
                    allDay -> "That day has passed, so this reminder will be filed under Archived."
                    else -> "That time has passed, so this reminder will be filed under Archived."
                },
                style = XpType.Small,
                color = XpColors.textDim,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            FieldLabel("Location")
            XpTextField(
                location,
                { location = it },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            FieldLabel("Notes")
            XpTextField(
                notes,
                { notes = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 3,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        }
        XpGroupBox("Calendar", Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                // A repeating reminder cannot be a delivery.
                val domainOptions = if (repeating || repeatMode == RepeatEditMode.SERIES) {
                    listOf(EventDomain.LIFE, EventDomain.SCHOOL)
                } else {
                    listOf(EventDomain.LIFE, EventDomain.SCHOOL, EventDomain.DELIVERY)
                }
                domainOptions.forEach { option ->
                    XpRadioButton(domain == option, { chooseDomain(option) }, option.displayName)
                }
            }
            Row(Modifier.padding(top = 2.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Category", style = XpType.Small, maxLines = 1)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    XpComboBox(
                        category.displayName,
                        { categoryMenu = true },
                        Modifier.fillMaxWidth(),
                        leading = { XpSwatch(Color(category.colorArgb)) },
                    )
                    XpPopupMenu(expanded = categoryMenu, onDismiss = { categoryMenu = false }) {
                        EventCategory.entries.filter { !repeating || it != EventCategory.DELIVERY }.forEach { option ->
                            XpMenuItem(
                                option.displayName,
                                onClick = {
                                    category = option
                                    when {
                                        option == EventCategory.DELIVERY -> chooseDomain(EventDomain.DELIVERY)
                                        // A delivery stays one when it is only given another color.
                                        option == EventCategory.AUTO || delivery -> Unit
                                        option == EventCategory.SCHOOL -> chooseDomain(EventDomain.SCHOOL)
                                        else -> chooseDomain(EventDomain.LIFE)
                                    }
                                    categoryMenu = false
                                },
                                leading = { XpSwatch(Color(option.colorArgb)) },
                            )
                        }
                    }
                }
            }
        }
        if (repeatMode != RepeatEditMode.NONE && !delivery) {
            RepeatGroup(
                mode = repeatMode,
                frequency = repeat,
                onFrequency = { next ->
                    if (next == RepeatFrequency.WEEKLY && weekdays.isEmpty()) weekdays = setOf(date.dayOfWeek.value)
                    repeat = next
                },
                intervalText = intervalText,
                onIntervalText = { intervalText = it.filter(Char::isDigit).take(3) },
                weekdays = weekdays,
                onToggleWeekday = { day -> weekdays = if (day in weekdays) weekdays - day else weekdays + day },
                end = repeatEnd,
                onEnd = { repeatEnd = it },
                until = repeatUntil,
                onPickUntil = { pickingUntil = true },
                countText = countText,
                onCountText = { countText = it.filter(Char::isDigit).take(4) },
                summary = repeatChoice()?.takeIf { it.validationError(date) == null }?.let { RepeatText.describe(it.rule(date, time)) },
            )
        }
        if (delivery) {
            XpGroupBox("Remind me", Modifier.fillMaxWidth()) {
                Row(Modifier.padding(top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(XpIcons.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Deliveries never alert. This one stays listed through the day it is expected.", style = XpType.Small)
                }
            }
        } else {
            XpGroupBox("Remind me", Modifier.fillMaxWidth()) {
                val choices = (standardOffsets + offsets).distinct().sorted()
                choices.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth()) {
                        pair.forEach { minutes ->
                            XpCheckBox(
                                checked = minutes in offsets,
                                onCheckedChange = { on -> offsets = if (on) (offsets + minutes).distinct() else offsets - minutes },
                                label = describeReminderOffset(minutes).replaceFirstChar { it.uppercase() },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                XpEtchedLine(Modifier.padding(vertical = 4.dp))
                XpCheckBox(
                    checked = critical,
                    onCheckedChange = { on ->
                        offsets = offsetsAfterCriticalChange(offsets, critical, on)
                        critical = on
                    },
                    label = "Critical: exact alarm, heads-up alert",
                )
                // Says so when the chosen time would put an alert in the middle of the night.
                ReminderSchedule.movedNote(startMillis, offsets, zone)?.let { note ->
                    Text(note, style = XpType.Small, color = XpColors.textDim, modifier = Modifier.padding(top = 2.dp, bottom = 3.dp))
                }
            }
        }
        (error ?: saveError)?.let { message ->
            // The editor is taller than the screen with the Repeat box open: scroll the problem into view.
            val errorView = remember { BringIntoViewRequester() }
            LaunchedEffect(message) { errorView.bringIntoView() }
            Text(message, style = XpType.Small, color = XpColors.errorText, modifier = Modifier.bringIntoViewRequester(errorView))
        }
    }

    if (pickingDate) {
        DatePickerDialog(
            initial = date,
            onDismiss = { pickingDate = false },
            onPick = {
                date = it
                pickingDate = false
            },
        )
    }
    if (pickingUntil) {
        DatePickerDialog(
            initial = repeatUntil ?: date.plusMonths(3),
            onDismiss = { pickingUntil = false },
            onPick = {
                repeatUntil = it
                repeatEnd = RepeatEnd.ON_DATE
                pickingUntil = false
            },
        )
    }
    if (pickingTime) {
        TimePickerDialog(
            initial = time,
            onDismiss = { pickingTime = false },
            onPick = {
                time = it
                pickingTime = false
            },
        )
    }
}

private val untilFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

/**
 * The editor's Repeat box: the pattern, every N, the days of a weekly pattern and how it ends. In
 * [RepeatEditMode.SERIES] "Does not repeat" is not offered: the change is to the series itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepeatGroup(
    mode: RepeatEditMode,
    frequency: RepeatFrequency?,
    onFrequency: (RepeatFrequency?) -> Unit,
    intervalText: String,
    onIntervalText: (String) -> Unit,
    weekdays: Set<Int>,
    onToggleWeekday: (Int) -> Unit,
    end: RepeatEnd,
    onEnd: (RepeatEnd) -> Unit,
    until: LocalDate?,
    onPickUntil: () -> Unit,
    countText: String,
    onCountText: (String) -> Unit,
    summary: String?,
) {
    var menu by remember { mutableStateOf(false) }
    XpGroupBox("Repeat", Modifier.fillMaxWidth()) {
        Row(Modifier.padding(top = 2.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Repeat", style = XpType.Small, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                XpComboBox(
                    frequency?.displayName ?: "Does not repeat",
                    { menu = true },
                    Modifier.fillMaxWidth(),
                    leading = { Image(XpIcons.Repeat, contentDescription = null, modifier = Modifier.size(16.dp)) },
                )
                XpPopupMenu(expanded = menu, onDismiss = { menu = false }) {
                    if (mode == RepeatEditMode.NEW) {
                        XpMenuItem("Does not repeat", onClick = {
                            onFrequency(null)
                            menu = false
                        })
                    }
                    RepeatFrequency.entries.forEach { option ->
                        XpMenuItem(option.displayName, onClick = {
                            onFrequency(option)
                            menu = false
                        })
                    }
                }
            }
        }
        if (frequency != null) {
            Row(Modifier.padding(bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Every", style = XpType.Small)
                Spacer(Modifier.width(8.dp))
                XpTextField(
                    intervalText,
                    onIntervalText,
                    modifier = Modifier.width(64.dp),
                    textStyle = XpType.Body.copy(textAlign = TextAlign.Center),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                )
                Spacer(Modifier.width(8.dp))
                Text(if (intervalText == "1") frequency.unit else "${frequency.unit}s", style = XpType.Small)
            }
            if (frequency == RepeatFrequency.WEEKLY) {
                FlowRow(Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
                    (1..7).forEach { day ->
                        XpCheckBox(
                            checked = day in weekdays,
                            onCheckedChange = { onToggleWeekday(day) },
                            label = RepeatText.weekdayName(day).take(3),
                            modifier = Modifier.width(92.dp),
                        )
                    }
                }
            }
            XpEtchedLine(Modifier.padding(vertical = 3.dp))
            Text("Ends", style = XpType.Small)
            XpRadioButton(end == RepeatEnd.NEVER, { onEnd(RepeatEnd.NEVER) }, "Never")
            Row(verticalAlignment = Alignment.CenterVertically) {
                XpRadioButton(end == RepeatEnd.ON_DATE, { if (until == null) onPickUntil() else onEnd(RepeatEnd.ON_DATE) }, "On")
                Spacer(Modifier.width(8.dp))
                XpComboBox(until?.format(untilFormatter) ?: "Choose date", onPickUntil, Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                XpRadioButton(end == RepeatEnd.AFTER, { onEnd(RepeatEnd.AFTER) }, "After")
                Spacer(Modifier.width(8.dp))
                XpTextField(
                    countText,
                    {
                        onCountText(it)
                        onEnd(RepeatEnd.AFTER)
                    },
                    modifier = Modifier.width(72.dp),
                    textStyle = XpType.Body.copy(textAlign = TextAlign.Center),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                )
                Spacer(Modifier.width(8.dp))
                Text("times", style = XpType.Small)
            }
            summary?.let { Text(it, style = XpType.Small, color = XpColors.textDim, modifier = Modifier.padding(top = 3.dp, bottom = 2.dp)) }
        }
    }
}

/** Which occurrences of a repeating reminder an edit or a delete applies to. */
enum class SeriesScope { THIS_ONE, ALL_FUTURE }

/**
 * Asked before editing or deleting an occurrence of a repeating reminder: this one, or this and all
 * future ones. [action] is the default button's label ("Edit", "Delete").
 */
@Composable
internal fun SeriesScopeDialog(
    title: String,
    eventTitle: String,
    action: String,
    onDismiss: () -> Unit,
    onChoose: (SeriesScope) -> Unit,
) {
    var scope by remember { mutableStateOf(SeriesScope.THIS_ONE) }
    XpDialog(
        title = title,
        onDismiss = onDismiss,
        dismissOnOutside = true,
        buttons = {
            XpButton(action, onClick = { onChoose(scope) }, default = true)
            XpButton("Cancel", onClick = onDismiss)
        },
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Image(XpIcons.Repeat, contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(36.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("\"$eventTitle\" is a repeating reminder.", style = XpType.Body)
                XpRadioButton(scope == SeriesScope.THIS_ONE, { scope = SeriesScope.THIS_ONE }, "This one")
                XpRadioButton(scope == SeriesScope.ALL_FUTURE, { scope = SeriesScope.ALL_FUTURE }, "This and all future ones")
            }
        }
    }
}

@Composable
internal fun DatePickerDialog(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    var month by remember { mutableStateOf(YearMonth.from(initial)) }
    var selected by remember { mutableStateOf(initial) }
    val today = remember { LocalDate.now() }
    XpDialog(
        title = "Date",
        onDismiss = onDismiss,
        dismissOnOutside = true,
        buttons = {
            XpButton("OK", onClick = { onPick(selected) }, default = true)
            XpButton("Cancel", onClick = onDismiss)
        },
    ) {
        XpMonthGrid(
            month = month,
            selectedDate = selected,
            today = today,
            onSelectDate = {
                selected = it
                month = YearMonth.from(it)
            },
            onMonthChange = { month = it },
            cellHeight = 40.dp,
        )
        Text(selected.format(pickedDateFormatter), style = XpType.Small)
    }
}

private fun hour12(time: LocalTime): Int = if (time.hour % 12 == 0) 12 else time.hour % 12

private val presetTimes = listOf(
    LocalTime.of(8, 0),
    LocalTime.of(9, 0),
    LocalTime.of(12, 0),
    LocalTime.of(17, 0),
    LocalTime.of(23, 59),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TimePickerDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    var hourText by remember { mutableStateOf(hour12(initial).toString()) }
    var minuteText by remember { mutableStateOf(initial.minute.toString().padStart(2, '0')) }
    var pm by remember { mutableStateOf(initial.hour >= 12) }
    var error by remember { mutableStateOf<String?>(null) }

    fun parsed(): LocalTime? {
        val hour = hourText.toIntOrNull() ?: return null
        val minute = minuteText.toIntOrNull() ?: return null
        if (hour !in 1..12 || minute !in 0..59) return null
        return LocalTime.of(hour % 12 + if (pm) 12 else 0, minute)
    }

    fun show(time: LocalTime) {
        hourText = hour12(time).toString()
        minuteText = time.minute.toString().padStart(2, '0')
        pm = time.hour >= 12
        error = null
    }

    fun stepHour(delta: Int) {
        val hour = (hourText.toIntOrNull() ?: 12).coerceIn(1, 12)
        hourText = ((hour - 1 + delta + 12) % 12 + 1).toString()
        error = null
    }

    // Arrows move to the next/previous five-minute mark; exact minutes can still be typed.
    fun stepMinute(delta: Int) {
        val minute = (minuteText.toIntOrNull() ?: 0).coerceIn(0, 59)
        val next = when {
            delta > 0 -> (minute / 5 + 1) * 5
            minute % 5 == 0 -> minute - 5
            else -> minute / 5 * 5
        }
        minuteText = ((next + 60) % 60).toString().padStart(2, '0')
        error = null
    }

    XpDialog(
        title = "Time",
        onDismiss = onDismiss,
        dismissOnOutside = true,
        buttons = {
            XpButton(
                "OK",
                onClick = {
                    val time = parsed()
                    if (time == null) error = "Enter an hour from 1 to 12 and minutes from 00 to 59." else onPick(time)
                },
                default = true,
            )
            XpButton("Cancel", onClick = onDismiss)
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SpinField(hourText, { hourText = it.filter(Char::isDigit).take(2) }, "Hour", { stepHour(1) }, { stepHour(-1) })
            Text(":", style = XpType.BodyBold.copy(fontSize = 20.sp), modifier = Modifier.padding(horizontal = 7.dp))
            SpinField(minuteText, { minuteText = it.filter(Char::isDigit).take(2) }, "Minute", { stepMinute(1) }, { stepMinute(-1) })
            Spacer(Modifier.width(16.dp))
            Column {
                XpRadioButton(!pm, { pm = false }, "AM")
                XpRadioButton(pm, { pm = true }, "PM")
            }
        }
        XpEtchedLine()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            presetTimes.forEach { preset ->
                XpButton(preset.format(editorTimeFormatter), onClick = { show(preset) }, minWidth = 0.dp)
            }
        }
        error?.let { Text(it, style = XpType.Small, color = XpColors.errorText) }
    }
}

/** Number box with the stacked up/down arrows of a spin control. */
@Composable
private fun SpinField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        XpTextField(
            value,
            onValueChange,
            modifier = Modifier.width(58.dp),
            textStyle = XpType.Body.copy(fontSize = 20.sp, lineHeight = 26.sp, textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        )
        Column(Modifier.padding(start = 2.dp)) {
            XpGlyphButton("$label up", onUp, width = 44.dp, height = 24.dp) { drawXpArrow(XpArrow.UP, it, extent = size.minDimension * 0.5f) }
            XpGlyphButton("$label down", onDown, width = 44.dp, height = 24.dp) { drawXpArrow(XpArrow.DOWN, it, extent = size.minDimension * 0.5f) }
        }
    }
}

@Composable
internal fun ConnectionDialog(
    initial: BridgeConfig?,
    connected: Boolean,
    busy: Boolean,
    lastSyncMillis: Long,
    pendingCount: Int,
    issue: String?,
    error: String?,
    onDismissIssue: () -> Unit,
    onSyncNow: () -> Unit,
    onSyncCanvas: () -> Unit,
    onDismiss: () -> Unit,
    onConnect: (String, String) -> Unit,
) {
    var serverUrl by remember { mutableStateOf(initial?.serverUrl.orEmpty()) }
    var pairingCode by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }

    XpDialog(
        title = "Cloud Connection",
        onDismiss = onDismiss,
        buttons = {
            XpButton(
                if (connected) "Reconnect" else "Connect",
                onClick = {
                    when {
                        serverUrl.isBlank() || pairingCode.isBlank() -> inputError = "Enter the bridge URL and the pairing code."
                        !serverUrl.trim().startsWith("https://") -> inputError = "Bridge URL must use HTTPS."
                        else -> {
                            inputError = null
                            onConnect(serverUrl.trim(), pairingCode.trim())
                        }
                    }
                },
                enabled = !busy,
                default = !connected,
            )
            XpButton("Close", onClick = onDismiss)
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(if (connected) XpIcons.CloudOk else XpIcons.CloudOff, contentDescription = null, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (connected) "Connected" else "Not connected", style = XpType.BodyBold)
                Text(
                    when {
                        busy -> "Working..."
                        !connected -> "Pair this phone to receive reminders from the cloud."
                        lastSyncMillis <= 0L -> "Not synced yet."
                        else -> "Last sync: ${formatEventDate(lastSyncMillis)}"
                    },
                    style = XpType.Small,
                    color = XpColors.textDim,
                )
                if (pendingCount > 0) {
                    Text(
                        "$pendingCount change${if (pendingCount == 1) "" else "s"} waiting to sync",
                        style = XpType.Small,
                        color = XpColors.textDim,
                    )
                }
            }
        }
        if (issue != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(XpColors.tooltip)
                    .border(1.dp, XpColors.shadow)
                    .padding(start = 9.dp, end = 9.dp, top = 6.dp, bottom = 2.dp),
            ) {
                Text(issue, style = XpType.Small, color = XpColors.tooltipText)
                XpLink("Dismiss", onDismissIssue, style = XpType.Small)
            }
        }
        if (connected) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                XpButton("Sync Now", onClick = onSyncNow, enabled = !busy, icon = XpIcons.Sync)
                XpButton("Sync Canvas", onClick = onSyncCanvas, enabled = !busy, icon = XpIcons.School)
            }
        }
        XpGroupBox(if (connected) "Pair again" else "Pairing", Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                FieldLabel("Bridge HTTPS URL")
                XpTextField(
                    serverUrl,
                    { serverUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "https://",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                )
                Spacer(Modifier.size(3.dp))
                FieldLabel("Pairing code")
                XpTextField(
                    pairingCode,
                    { pairingCode = it },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }
        }
        (inputError ?: error)?.let { Text(it, style = XpType.Small, color = XpColors.errorText) }
    }
}

/** Tools > Options. A change applies at once, so there is nothing to confirm or cancel. */
@Composable
internal fun OptionsDialog(darkMode: Boolean, onDarkModeChange: (Boolean) -> Unit, onDismiss: () -> Unit) {
    XpDialog(
        title = "Options",
        onDismiss = onDismiss,
        dismissOnOutside = true,
        buttons = { XpButton("Close", onClick = onDismiss, default = true) },
    ) {
        XpGroupBox("Appearance") {
            XpCheckBox(darkMode, onDarkModeChange, "Dark mode", Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun AboutDialog(
    versionName: String,
    upcomingCount: Int,
    archivedCount: Int,
    notificationsReady: Boolean,
    exactAlarmReady: Boolean,
    connected: Boolean,
    lastSyncMillis: Long,
    onDismiss: () -> Unit,
) {
    XpDialog(
        title = "About AI Planner",
        onDismiss = onDismiss,
        dismissOnOutside = true,
        buttons = { XpButton("OK", onClick = onDismiss, default = true) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(XpIcons.App, contentDescription = null, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("AI Planner", style = XpType.BodyBold.copy(fontSize = 19.sp, lineHeight = 24.sp))
                Text("Version $versionName", style = XpType.Small, color = XpColors.textDim)
            }
        }
        XpEtchedLine()
        AboutLine("Reminders", "$upcomingCount upcoming, $archivedCount archived")
        AboutLine("Notifications", if (notificationsReady) "Allowed" else "Blocked")
        AboutLine("Exact alarms", if (exactAlarmReady) "Allowed" else "Not allowed")
        AboutLine(
            "Cloud",
            when {
                !connected -> "Not connected"
                lastSyncMillis <= 0L -> "Connected"
                else -> "Connected, last sync ${formatEventDate(lastSyncMillis)}"
            },
        )
    }
}

@Composable
private fun AboutLine(label: String, value: String) {
    Row {
        Text(label, modifier = Modifier.width(104.dp), style = XpType.Small.copy(fontWeight = FontWeight.SemiBold))
        Text(value, modifier = Modifier.weight(1f), style = XpType.Small)
    }
}
