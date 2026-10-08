package com.example.aicalendar

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aicalendar.xp.XpArrow
import com.example.aicalendar.xp.XpButton
import com.example.aicalendar.xp.XpColors
import com.example.aicalendar.xp.XpGlyphButton
import com.example.aicalendar.xp.XpIcons
import com.example.aicalendar.xp.XpType
import com.example.aicalendar.xp.drawXpArrow
import com.example.aicalendar.xp.xpClickable
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** What a day cell shows under its number: up to three colored dots and an assessment marker. */
internal class DayMarks(val dots: List<Color>, val assessment: Boolean)

private val monthTitleFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")
private val dayDescriptionFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d")
// Weeks run Monday to Sunday here, as they do in the list's "This week" and "Next week" groups.
private val weekdayLabels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** How many blank cells come before the 1st: none when the month starts on a Monday, six on a Sunday. */
internal fun leadingBlankDays(month: YearMonth): Int = month.atDay(1).dayOfWeek.value - 1

/**
 * Month calendar in the classic date-picker style: flat grid, blue selection, red ring on today.
 * Used by the calendar view (with [marks]) and by the editor's date picker.
 */
@Composable
internal fun XpMonthGrid(
    month: YearMonth,
    selectedDate: LocalDate?,
    today: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
    onMonthChange: (YearMonth) -> Unit,
    modifier: Modifier = Modifier,
    cellHeight: Dp = 50.dp,
    marks: (LocalDate) -> DayMarks? = { null },
) {
    val firstDayOffset = leadingBlankDays(month)
    val daysInMonth = month.lengthOfMonth()
    val weeks = (firstDayOffset + daysInMonth + 6) / 7

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            XpGlyphButton("Previous month", { onMonthChange(month.minusMonths(1)) }) { drawXpArrow(XpArrow.LEFT, it) }
            Text(
                month.format(monthTitleFormatter),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = XpType.BodyBold,
            )
            XpButton(
                "Today",
                onClick = {
                    onMonthChange(YearMonth.from(today))
                    onSelectDate(today)
                },
                minWidth = 66.dp,
            )
            Spacer(Modifier.width(6.dp))
            XpGlyphButton("Next month", { onMonthChange(month.plusMonths(1)) }) { drawXpArrow(XpArrow.RIGHT, it) }
        }
        Column(Modifier.fillMaxWidth().background(XpColors.window).border(1.dp, XpColors.fieldBorder).padding(1.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 4.dp)) {
                weekdayLabels.forEach { label ->
                    Text(
                        label,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = XpType.Tiny,
                        color = XpColors.groupText,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(1.dp).background(XpColors.shadow))
            repeat(weeks) { week ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { weekday ->
                        val dayNumber = week * 7 + weekday - firstDayOffset + 1
                        val date = if (dayNumber in 1..daysInMonth) month.atDay(dayNumber) else null
                        DayCell(
                            date = date,
                            selected = date != null && date == selectedDate,
                            isToday = date != null && date == today,
                            marks = date?.let(marks),
                            height = cellHeight,
                            onClick = onSelectDate,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate?,
    selected: Boolean,
    isToday: Boolean,
    marks: DayMarks?,
    height: Dp,
    onClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (date == null) {
        Spacer(modifier.height(height))
        return
    }
    val shape = RoundedCornerShape(3.dp)
    Column(
        modifier
            .height(height)
            .padding(1.dp)
            .background(if (selected) XpColors.highlight else Color.Transparent, shape)
            .then(if (isToday) Modifier.border(1.5.dp, XpColors.today, shape) else Modifier)
            .semantics { contentDescription = date.format(dayDescriptionFormatter) }
            .xpClickable(role = Role.Button) { onClick(date) }
            .padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            date.dayOfMonth.toString(),
            style = XpType.Body.copy(fontSize = 14.sp, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal),
            color = if (selected) Color.White else XpColors.text,
        )
        if (marks != null && (marks.assessment || marks.dots.isNotEmpty())) {
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                if (marks.assessment) {
                    Image(XpIcons.Warning, contentDescription = "Assessment due", modifier = Modifier.size(11.dp))
                }
                marks.dots.take(3).forEach { dot ->
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(dot, CircleShape)
                            .border(0.5.dp, if (selected) Color.White else XpColors.swatchEdge, CircleShape),
                    )
                }
            }
        }
    }
}
