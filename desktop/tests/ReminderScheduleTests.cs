using System.Globalization;

namespace AICalendar.Desktop.Tests;

/// <summary>The same cases as ReminderScheduleTest.kt in the phone app: both must alert at the same moments.</summary>
public sealed class ReminderScheduleTests
{
    private static readonly TimeZoneInfo Denver = TimeZoneInfo.FindSystemTimeZoneById("America/Denver");

    private static DateTimeOffset At(string text)
    {
        var wall = DateTime.Parse(text, CultureInfo.InvariantCulture);
        return new DateTimeOffset(wall, Denver.GetUtcOffset(wall));
    }

    /// <summary>"kind@local time" for each planned alarm, in firing order.</summary>
    private static string[] Plan(string start, params int[] offsets) =>
        ReminderSchedule.Plan(At(start), offsets, Denver)
            .Select(alarm => $"{alarm.Kind}@{TimeZoneInfo.ConvertTime(alarm.TriggerAt, Denver).ToString("yyyy-MM-dd'T'HH:mm", CultureInfo.InvariantCulture)}")
            .ToArray();

    [Fact]
    public void AssignmentDueAtEndOfDayAlertsInTheEvening()
    {
        // A Canvas quiz due Friday 11:59 PM used to alert at 11:59 PM on Tuesday, Thursday and Friday.
        Assert.Equal(
            ["offset_4320@2026-10-06T20:00", "offset_1440@2026-10-08T20:00", "tonight@2026-10-09T20:00"],
            Plan("2026-10-09T23:59:59", 4320, 1440, 120, 60, 0));
    }

    [Fact]
    public void MidnightDeadlineBelongsToTheDayThatIsEnding()
    {
        Assert.Equal(
            ["offset_1440@2026-10-08T20:00", "tonight@2026-10-09T20:00"],
            Plan("2026-10-10T00:00", 1440, 0));
    }

    [Fact]
    public void DaytimeEventsAreLeftAlone()
    {
        Assert.Equal(
            ["offset_1440@2026-10-07T13:00", "offset_60@2026-10-08T12:00", "offset_0@2026-10-08T13:00"],
            Plan("2026-10-08T13:00", 1440, 60, 0));
        Assert.Null(ReminderSchedule.MovedNote(At("2026-10-08T13:00"), [1440, 60, 0], Denver));
    }

    [Fact]
    public void EarlyFlightKeepsItsWakeUpAlertsButNotTheDayBeforeOne()
    {
        Assert.Equal(
            ["offset_1440@2026-10-25T08:00", "offset_180@2026-10-26T03:15", "offset_0@2026-10-26T06:15"],
            Plan("2026-10-26T06:15", 1440, 180, 0));
    }

    [Fact]
    public void LateEventThatIsNotADeadlineKeepsItsShortAlerts()
    {
        Assert.Equal(
            ["offset_1440@2026-10-01T20:00", "offset_60@2026-10-02T22:30", "offset_0@2026-10-02T23:30"],
            Plan("2026-10-02T23:30", 1440, 60, 0));
    }

    [Fact]
    public void NightBoundariesAreNinePmAndEightAm()
    {
        Assert.Equal(["offset_1440@2026-10-07T20:59"], Plan("2026-10-08T20:59", 1440));
        Assert.Equal(["offset_1440@2026-10-07T20:00"], Plan("2026-10-08T21:00", 1440));
        Assert.Equal(["offset_1440@2026-10-07T08:00"], Plan("2026-10-08T07:59", 1440));
        Assert.Equal(["offset_1440@2026-10-07T08:00"], Plan("2026-10-08T08:00", 1440));
    }

    [Fact]
    public void HalfDayNoticeCountsAsAdvanceNotice()
    {
        // 12 hours before a 10 AM event is 10 PM the evening before.
        Assert.Equal(["offset_720@2026-10-07T20:00"], Plan("2026-10-08T10:00", 720));
        // A shorter lead on a daytime event is a last-minute alert and stays put.
        Assert.Equal(["offset_600@2026-10-08T00:30"], Plan("2026-10-08T10:30", 600));
    }

    [Fact]
    public void EndOfDayDeadlineKeepsEarlierSameDayAlerts()
    {
        Assert.Equal(
            ["offset_360@2026-10-09T17:59", "tonight@2026-10-09T20:00"],
            Plan("2026-10-09T23:59", 360, 60, 0));
    }

    [Fact]
    public void DaysBeforeKeepsTheClockTimeAcrossDaylightSaving()
    {
        // Clocks go back on Nov 1, 2026: a day before Sunday 11:59 PM is still Saturday evening.
        Assert.Equal(["offset_1440@2026-10-31T20:00"], Plan("2026-11-01T23:59", 1440));
        Assert.Equal(["offset_1440@2026-10-31T13:00"], Plan("2026-11-01T13:00", 1440));
    }

    [Fact]
    public void NoteNamesTheTimesAlertsMoveTo()
    {
        Assert.Equal("Night-time alerts arrive at 8:00 PM.", ReminderSchedule.MovedNote(At("2026-10-09T23:59"), [4320, 1440, 0], Denver));
        Assert.Equal("Night-time alerts arrive at 8:00 AM.", ReminderSchedule.MovedNote(At("2026-10-26T06:15"), [1440, 0], Denver));
        Assert.Null(ReminderSchedule.MovedNote(At("2026-10-09T23:59"), [], Denver));
    }

    [Fact]
    public void DuplicateOffsetsAndNoOffsets()
    {
        Assert.Equal(["offset_1440@2026-10-07T13:00"], Plan("2026-10-08T13:00", 1440, 1440));
        Assert.Empty(Plan("2026-10-08T13:00"));
    }
}
