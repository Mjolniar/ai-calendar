using System.Globalization;

namespace AICalendar.Desktop.Tests;

/// <summary>The same cases as PlannerFormatTest.kt in the phone app: both list and word reminders alike.</summary>
public sealed class ReminderTextTests
{
    // Thursday; the week ends Sunday, October 4.
    private static readonly DateOnly Today = new(2026, 10, 1);

    private static CalendarEvent Event(string id, DateOnly date, int hour = 9) => new()
    {
        Id = id,
        Title = $"Item {id}",
        // No offset: wall-clock time in the event's zone, here the zone of the PC running the test.
        StartAt = date.ToDateTime(new TimeOnly(hour, 0)).ToString("yyyy-MM-dd'T'HH:mm:ss", CultureInfo.InvariantCulture),
        TimeZone = TimeZoneInfo.Local.Id,
    };

    private static string Label(DateOnly date, DateOnly today) => ReminderText.GroupFor(date, today).Label;

    private static string[] Describe(IEnumerable<ListEntry> entries) => entries.Select(entry => entry switch
    {
        GroupEntry group => $"{group.Group.Label} ({group.Count})",
        EventEntry single => single.Event.Id,
        _ => "?",
    }).ToArray();

    [Fact]
    public void DueGroupsFollowTheCalendarWeek()
    {
        Assert.Equal("Today", Label(Today, Today));
        Assert.Equal("Tomorrow", Label(Today.AddDays(1), Today));
        Assert.Equal("This week", Label(new DateOnly(2026, 10, 3), Today));
        // Sunday still belongs to this week; next week starts on Monday and runs through Sunday.
        Assert.Equal("This week", Label(new DateOnly(2026, 10, 4), Today));
        Assert.Equal("Next week", Label(new DateOnly(2026, 10, 5), Today));
        Assert.Equal("Next week", Label(new DateOnly(2026, 10, 11), Today));
        Assert.Equal("Later this month", Label(new DateOnly(2026, 10, 12), Today));
        Assert.Equal("November", Label(new DateOnly(2026, 11, 2), Today));
        Assert.Equal("January 2027", Label(new DateOnly(2027, 1, 5), Today));
        Assert.Equal("Earlier", Label(Today.AddDays(-1), Today));
    }

    [Fact]
    public void OnTheWeekendNextWeekStillStartsOnMonday()
    {
        var saturday = new DateOnly(2026, 10, 3);
        Assert.Equal("Tomorrow", Label(saturday.AddDays(1), saturday));
        Assert.Equal("Next week", Label(new DateOnly(2026, 10, 5), saturday));
        Assert.Equal("Next week", Label(new DateOnly(2026, 10, 11), saturday));
        Assert.Equal("Later this month", Label(new DateOnly(2026, 10, 12), saturday));

        // Sunday is the last day of its week: nothing is left for "This week".
        var sunday = new DateOnly(2026, 10, 4);
        Assert.Equal("Today", Label(sunday, sunday));
        Assert.Equal("Tomorrow", Label(new DateOnly(2026, 10, 5), sunday));
        Assert.Equal("Next week", Label(new DateOnly(2026, 10, 6), sunday));
        Assert.Equal("Next week", Label(new DateOnly(2026, 10, 11), sunday));
        Assert.Equal("Later this month", Label(new DateOnly(2026, 10, 12), sunday));
    }

    [Fact]
    public void OnMondayTheWholeWeekIsThisWeek()
    {
        var monday = new DateOnly(2026, 10, 5);
        Assert.Equal("Tomorrow", Label(new DateOnly(2026, 10, 6), monday));
        Assert.Equal("This week", Label(new DateOnly(2026, 10, 7), monday));
        Assert.Equal("This week", Label(new DateOnly(2026, 10, 11), monday));
        Assert.Equal("Next week", Label(new DateOnly(2026, 10, 12), monday));
        Assert.Equal("Next week", Label(new DateOnly(2026, 10, 18), monday));
        Assert.Equal("Later this month", Label(new DateOnly(2026, 10, 19), monday));
    }

    [Fact]
    public void MonthGridStartsItsWeeksOnMonday()
    {
        // June 2026 starts on a Monday, November 2026 on a Sunday, October 2026 on a Thursday.
        Assert.Equal(0, ReminderText.LeadingBlankDays(new DateTime(2026, 6, 1)));
        Assert.Equal(6, ReminderText.LeadingBlankDays(new DateTime(2026, 11, 1)));
        Assert.Equal(3, ReminderText.LeadingBlankDays(new DateTime(2026, 10, 1)));
    }

    [Fact]
    public void NextWeekCanReachIntoTheNextMonth()
    {
        var lateOctober = new DateOnly(2026, 10, 28);
        Assert.Equal("This week", Label(new DateOnly(2026, 10, 31), lateOctober));
        // The week of Wednesday, October 28 ends on Sunday, November 1.
        Assert.Equal("This week", Label(new DateOnly(2026, 11, 1), lateOctober));
        Assert.Equal("Next week", Label(new DateOnly(2026, 11, 2), lateOctober));
        Assert.Equal("Next week", Label(new DateOnly(2026, 11, 8), lateOctober));
        Assert.Equal("November", Label(new DateOnly(2026, 11, 9), lateOctober));
    }

    [Fact]
    public void ListEntriesPutOneHeaderAboveEachRun()
    {
        CalendarEvent[] events =
        [
            Event("a", Today),
            Event("b", Today, hour: 15),
            Event("c", Today.AddDays(1)),
            Event("d", new DateOnly(2026, 10, 20)),
        ];
        Assert.Equal(
            ["Today (2)", "a", "b", "Tomorrow (1)", "c", "Later this month (1)", "d"],
            Describe(ReminderText.BuildEntries(events, Today, archived: false)));
    }

    private static string Archive(DateOnly date, DateOnly today) => ReminderText.ArchiveGroupFor(date, today).Label;

    [Fact]
    public void ArchiveGroupsCountBackFromToday()
    {
        // Today is Thursday, October 1; its week began on Monday, September 28.
        Assert.Equal("Today", Archive(Today, Today));
        Assert.Equal("Yesterday", Archive(new DateOnly(2026, 9, 30), Today));
        Assert.Equal("Earlier this week", Archive(new DateOnly(2026, 9, 29), Today));
        Assert.Equal("Earlier this week", Archive(new DateOnly(2026, 9, 28), Today));
        Assert.Equal("Last week", Archive(new DateOnly(2026, 9, 27), Today));
        Assert.Equal("Last week", Archive(new DateOnly(2026, 9, 21), Today));
        Assert.Equal("September", Archive(new DateOnly(2026, 9, 20), Today));
        Assert.Equal("August", Archive(new DateOnly(2026, 8, 30), Today));
        Assert.Equal("December 2025", Archive(new DateOnly(2025, 12, 31), Today));
        // Filed before its date: not history yet.
        Assert.Equal("Not due yet", Archive(Today.AddDays(1), Today));
        Assert.Equal("Not due yet", Archive(new DateOnly(2026, 12, 13), Today));
    }

    [Fact]
    public void ArchiveHasEarlierThisMonthOnceTheMonthIsOldEnough()
    {
        // Thursday, October 22: this week began on October 19, last week on October 12.
        var late = new DateOnly(2026, 10, 22);
        Assert.Equal("Earlier this week", Archive(new DateOnly(2026, 10, 19), late));
        Assert.Equal("Last week", Archive(new DateOnly(2026, 10, 12), late));
        Assert.Equal("Earlier this month", Archive(new DateOnly(2026, 10, 11), late));
        Assert.Equal("Earlier this month", Archive(new DateOnly(2026, 10, 1), late));
        Assert.Equal("September", Archive(new DateOnly(2026, 9, 30), late));
    }

    [Fact]
    public void OnMondayTheArchiveHasNoEarlierThisWeek()
    {
        var monday = new DateOnly(2026, 10, 5);
        Assert.Equal("Yesterday", Archive(new DateOnly(2026, 10, 4), monday));
        Assert.Equal("Last week", Archive(new DateOnly(2026, 10, 3), monday));
        Assert.Equal("Last week", Archive(new DateOnly(2026, 9, 28), monday));
        Assert.Equal("September", Archive(new DateOnly(2026, 9, 27), monday));
    }

    [Fact]
    public void ArchivedEntriesRunFromTheLatestBackwardsWithEarlyOnesLast()
    {
        CalendarEvent[] filed =
        [
            Event("exam", new DateOnly(2026, 12, 13)),
            Event("class", new DateOnly(2026, 10, 6), hour: 15),
            Event("lunch", Today, hour: 13),
            Event("morning", Today),
            Event("yesterday", new DateOnly(2026, 9, 30)),
            Event("monday", new DateOnly(2026, 9, 28)),
            Event("last-week", new DateOnly(2026, 9, 24)),
            Event("old", new DateOnly(2026, 9, 2)),
        ];
        // Whatever order they arrive in, the archive comes out the same.
        var ordered = ReminderText.ArchiveOrder(filed.Reverse(), Today);
        Assert.Equal(
            [
                "Today (2)", "lunch", "morning",
                "Yesterday (1)", "yesterday",
                "Earlier this week (1)", "monday",
                "Last week (1)", "last-week",
                "September (1)", "old",
                "Not due yet (2)", "class", "exam",
            ],
            Describe(ReminderText.BuildEntries(ordered, Today, archived: true)));
    }

    [Fact]
    public void ArchivedRemindersAtTheSameMomentAreInTitleOrder()
    {
        CalendarEvent Titled(string id, string title)
        {
            var item = Event(id, new DateOnly(2026, 9, 27), hour: 23);
            item.Title = title;
            return item;
        }
        CalendarEvent[] filed = [Titled("3", "chapter 04 Quiz"), Titled("1", "Reflection"), Titled("2", "Case Study"), Titled("4", "Case Study")];
        Assert.Equal(["2", "4", "3", "1"], ReminderText.ArchiveOrder(filed, Today).Select(item => item.Id).ToArray());
    }

    [Fact]
    public void RelativeDueReadsNaturally()
    {
        var now = new DateTimeOffset(2026, 10, 1, 12, 0, 0, TimeSpan.Zero);
        Assert.Equal("now", ReminderText.RelativeDue(now.AddSeconds(20), now));
        Assert.Equal("in 45 min", ReminderText.RelativeDue(now.AddMinutes(45), now));
        Assert.Equal("in 2 h", ReminderText.RelativeDue(now.AddMinutes(90), now));
        Assert.Equal("in 11 h", ReminderText.RelativeDue(now.AddHours(11), now));
        Assert.Equal("in 3 days", ReminderText.RelativeDue(now.AddDays(3), now));
        Assert.Equal("30 min ago", ReminderText.RelativeDue(now.AddMinutes(-30), now));
        Assert.Equal("5 days ago", ReminderText.RelativeDue(now.AddDays(-5), now));
    }

    [Fact]
    public void SearchMatchesEveryTermAcrossFields()
    {
        var item = new CalendarEvent
        {
            Title = "Exp 3 Quiz - Nuclear Decay",
            Location = "Science Building 204",
            Notes = "Bring goggles",
            CourseCode = "CHM110L 4001",
        };
        Assert.True(ReminderText.Matches(item, ""));
        Assert.True(ReminderText.Matches(item, "  QUIZ "));
        Assert.True(ReminderText.Matches(item, "chm110l decay"));
        Assert.True(ReminderText.Matches(item, "goggles science"));
        Assert.False(ReminderText.Matches(item, "quiz biology"));
    }

    [Fact]
    public void CrossListedSectionsCollapseToTheCourseCode()
    {
        Assert.Equal("CHM110L", ReminderText.CompactCourseLabel("CHM110L 4001 CHM110L 4002 CHM110L 4201"));
        Assert.Equal("ETH110", ReminderText.CompactCourseLabel("ETH110 001"));
        Assert.Equal("NUTR150", ReminderText.CompactCourseLabel("NUTR150 4002 NUTR150 4006 NUTR150 4007"));
    }

    [Fact]
    public void OtherCourseLabelsAreLeftAlone()
    {
        Assert.Equal("ENGL 102", ReminderText.CompactCourseLabel("ENGL 102"));
        Assert.Equal("English Composition II", ReminderText.CompactCourseLabel("English Composition II"));
        Assert.Equal("MATH143 001 MATH144 002", ReminderText.CompactCourseLabel("MATH143 001 MATH144 002"));
        Assert.Equal("CHM110", ReminderText.CompactCourseLabel(" CHM110 "));
        Assert.Equal("", ReminderText.CompactCourseLabel(""));
    }

    [Fact]
    public void ReminderOffsetsReadLongestFirst()
    {
        Assert.Equal("1 week before, 1 day before, at event time", ReminderText.FormatOffsets([0, 10080, 1440]));
        Assert.Equal("3 days before", ReminderText.DescribeOffset(4320));
        Assert.Equal("2 hours before", ReminderText.DescribeOffset(120));
        Assert.Equal("45 minutes before", ReminderText.DescribeOffset(45));
    }

    [Fact]
    public void AlertTitlesSayHowFarOffTheReminderIs()
    {
        // The wording of the phone's notifications (NotificationHelper.kt).
        Assert.Equal("Tonight: Lab report", ReminderText.AlertTitle(ReminderSchedule.KindTonight, "Lab report"));
        Assert.Equal("Now: Lab report", ReminderText.AlertTitle("offset_0", "Lab report"));
        Assert.Equal("Tomorrow: Lab report", ReminderText.AlertTitle("offset_1440", "Lab report"));
        Assert.Equal("In 3 days: Lab report", ReminderText.AlertTitle("offset_4320", "Lab report"));
        Assert.Equal("In 7 days: Lab report", ReminderText.AlertTitle("offset_10080", "Lab report"));
        Assert.Equal("In 1 hour: Lab report", ReminderText.AlertTitle("offset_60", "Lab report"));
        Assert.Equal("In 2 hours: Lab report", ReminderText.AlertTitle("offset_120", "Lab report"));
        Assert.Equal("In 45 minutes: Lab report", ReminderText.AlertTitle("offset_45", "Lab report"));
    }
}
