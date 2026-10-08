using System.Globalization;

namespace AICalendar.Desktop;

/// <summary>One reminder popup to show for an event. <see cref="Kind"/> identifies it and picks the wording.</summary>
public readonly record struct PlannedAlarm(string Kind, DateTimeOffset TriggerAt);

/// <summary>
/// Decides when each reminder of an event is delivered. The same rule as the phone app
/// (ReminderSchedule.kt), so both devices alert at the same moments.
///
/// A reminder is set as "N minutes before the start". Taken literally, anything that ends at midnight
/// (an assignment due 11:59 PM) alerts at midnight three days out, at midnight the night before, and
/// at the deadline itself. So:
///
///  - An advance notice (half a day or more ahead) never arrives at night. One that would land
///    between 9 PM and 8 AM moves to 8 PM that evening, or to 8 AM that morning.
///  - A deadline at the very end of a day (11:59 PM or midnight) gets its late last-minute alerts as
///    a single "Tonight" alert at 8 PM that evening.
///  - Short alerts for anything else (a 6 AM flight, a late shift) stay exactly where they were set.
///
/// "N days before" means the same clock time N days earlier, also across a daylight-saving change.
/// </summary>
public static class ReminderSchedule
{
    /// <summary>The one evening alert that replaces the late alerts of an end-of-day deadline.</summary>
    public const string KindTonight = "tonight";

    public static readonly TimeOnly NightStart = new(21, 0);
    public static readonly TimeOnly NightEnd = new(8, 0);
    public static readonly TimeOnly Evening = new(20, 0);
    public static readonly TimeOnly Morning = new(8, 0);

    /// <summary>Lead time from which a reminder counts as an advance notice rather than a last-minute alert.</summary>
    public const int AdvanceMinutes = 720;

    private const int MinutesPerDay = 1440;

    public static string KindFor(int offsetMinutes) => $"offset_{offsetMinutes}";

    /// <summary>The alarms for an event, earliest first. Alarms already in the past are included.</summary>
    public static List<PlannedAlarm> Plan(DateTimeOffset start, IEnumerable<int> offsetsMinutes, TimeZoneInfo? zone = null)
    {
        zone ??= TimeZoneInfo.Local;
        var startWall = TimeZoneInfo.ConvertTime(start, zone).DateTime;
        var planned = new Dictionary<string, DateTimeOffset>();
        foreach (var offset in offsetsMinutes.Distinct())
        {
            var nominal = NominalTime(startWall, offset, zone);
            var wall = TimeZoneInfo.ConvertTime(nominal, zone).DateTime;
            if (!IsNight(TimeOnly.FromDateTime(wall))) planned[KindFor(offset)] = nominal;
            else if (offset >= AdvanceMinutes) planned[KindFor(offset)] = AtZone(MoveToDaytime(wall), zone);
            else if (EndsTheDay(startWall)) planned[KindTonight] = AtZone(LastEvening(startWall), zone);
            else planned[KindFor(offset)] = nominal;
        }
        return planned.Select(entry => new PlannedAlarm(entry.Key, entry.Value)).OrderBy(alarm => alarm.TriggerAt).ToList();
    }

    /// <summary>
    /// One sentence for the reminder's details when <see cref="Plan"/> moves an alert, e.g.
    /// "Night-time alerts arrive at 8:00 PM." Null when every alert fires exactly as set.
    /// </summary>
    public static string? MovedNote(DateTimeOffset start, IReadOnlyCollection<int> offsetsMinutes, TimeZoneInfo? zone = null)
    {
        zone ??= TimeZoneInfo.Local;
        var startWall = TimeZoneInfo.ConvertTime(start, zone).DateTime;
        var nominal = offsetsMinutes.Distinct().ToDictionary(KindFor, offset => NominalTime(startWall, offset, zone));
        var movedTo = Plan(start, offsetsMinutes, zone)
            .Where(alarm => !nominal.TryGetValue(alarm.Kind, out var set) || set != alarm.TriggerAt)
            .Select(alarm => TimeOnly.FromDateTime(TimeZoneInfo.ConvertTime(alarm.TriggerAt, zone).DateTime))
            .Distinct()
            .Order()
            .Select(time => time.ToString("h:mm tt", CultureInfo.InvariantCulture))
            .ToList();
        return movedTo.Count == 0 ? null : $"Night-time alerts arrive at {string.Join(" or ", movedTo)}.";
    }

    private static DateTimeOffset NominalTime(DateTime startWall, int offsetMinutes, TimeZoneInfo zone) =>
        AtZone(startWall.AddDays(-(offsetMinutes / MinutesPerDay)), zone).AddMinutes(-(offsetMinutes % MinutesPerDay));

    private static DateTimeOffset AtZone(DateTime wall, TimeZoneInfo zone) =>
        new(DateTime.SpecifyKind(wall, DateTimeKind.Unspecified), zone.GetUtcOffset(DateTime.SpecifyKind(wall, DateTimeKind.Unspecified)));

    private static bool IsNight(TimeOnly time) => time >= NightStart || time < NightEnd;

    /// <summary>
    /// True for the two clock times that mean "by the end of the day" rather than a real late event:
    /// 11:59 PM (Canvas sends 23:59:59) and midnight.
    /// </summary>
    private static bool EndsTheDay(DateTime startWall) =>
        (startWall.Hour == 23 && startWall.Minute == 59) || IsMidnight(startWall);

    private static bool IsMidnight(DateTime wall) => wall.Hour == 0 && wall.Minute == 0;

    /// <summary>8 PM on the evening an end-of-day deadline runs out (for midnight: the evening before).</summary>
    private static DateTime LastEvening(DateTime startWall) => startWall.AddMinutes(-1).Date + Evening.ToTimeSpan();

    private static DateTime MoveToDaytime(DateTime wall)
    {
        if (TimeOnly.FromDateTime(wall) >= NightStart) return wall.Date + Evening.ToTimeSpan();
        // Midnight on the dot closes the day before; anything later belongs to the new morning.
        if (IsMidnight(wall)) return wall.Date.AddDays(-1) + Evening.ToTimeSpan();
        return wall.Date + Morning.ToTimeSpan();
    }
}
