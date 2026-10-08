using System.Globalization;
using System.Text.RegularExpressions;

namespace AICalendar.Desktop;

/// <summary>A date bucket shown as a group header in the reminder list.</summary>
public sealed record DueGroup(string Key, string Label);

public abstract record ListEntry;

public sealed record GroupEntry(DueGroup Group, int Count) : ListEntry;

public sealed record EventEntry(CalendarEvent Event) : ListEntry;

/// <summary>
/// Display logic for the reminder list: grouping, wording and search. No UI types, so it is
/// unit-tested. It mirrors PlannerFormat.kt in the phone app; both apps group and word reminders alike.
/// </summary>
public static partial class ReminderText
{
    private static readonly CultureInfo English = CultureInfo.InvariantCulture;

    [GeneratedRegex(@"^[0-9]{2,5}[A-Za-z]?$")]
    private static partial Regex SectionNumber();

    [GeneratedRegex(@"\s+")]
    private static partial Regex Whitespace();

    /// <summary>Group for an upcoming reminder, like a file list sorted by date. Weeks run Monday to Sunday.</summary>
    public static DueGroup GroupFor(DateOnly date, DateOnly today)
    {
        // DayOfWeek counts from Sunday (0): this is the number of days left until the week's Sunday.
        var endOfWeek = today.AddDays((7 - (int)today.DayOfWeek) % 7);
        if (date < today) return new DueGroup("earlier", "Earlier");
        if (date == today) return new DueGroup("today", "Today");
        if (date == today.AddDays(1)) return new DueGroup("tomorrow", "Tomorrow");
        if (date <= endOfWeek) return new DueGroup("this-week", "This week");
        if (date <= endOfWeek.AddDays(7)) return new DueGroup("next-week", "Next week");
        if (date.Year == today.Year && date.Month == today.Month) return new DueGroup("this-month", "Later this month");
        var key = $"m:{date:yyyy-MM}";
        return new DueGroup(key, date.ToString(date.Year == today.Year ? "MMMM" : "MMMM yyyy", English));
    }

    /// <summary>
    /// How many blank cells the month grid shows before the 1st. Its weeks run Monday to Sunday, like
    /// the list's groups: none when the month starts on a Monday, six when it starts on a Sunday.
    /// </summary>
    public static int LeadingBlankDays(DateTime firstOfMonth) => ((int)firstOfMonth.DayOfWeek + 6) % 7;

    /// <summary>
    /// Group for a reminder in the Archived list, counted back from today. Weeks run Monday to Sunday.
    /// A reminder filed ahead of its date (done early) is "Not due yet".
    /// </summary>
    public static DueGroup ArchiveGroupFor(DateOnly date, DateOnly today)
    {
        // DayOfWeek counts from Sunday (0): this is the number of days since the week's Monday.
        var startOfWeek = today.AddDays(-(((int)today.DayOfWeek + 6) % 7));
        if (date > today) return new DueGroup("ahead", "Not due yet");
        if (date == today) return new DueGroup("today", "Today");
        if (date == today.AddDays(-1)) return new DueGroup("yesterday", "Yesterday");
        if (date >= startOfWeek) return new DueGroup("this-week", "Earlier this week");
        if (date >= startOfWeek.AddDays(-7)) return new DueGroup("last-week", "Last week");
        if (date.Year == today.Year && date.Month == today.Month) return new DueGroup("this-month", "Earlier this month");
        return new DueGroup($"m:{date:yyyy-MM}", date.ToString(date.Year == today.Year ? "MMMM" : "MMMM yyyy", English));
    }

    /// <summary>
    /// The order of the Archived list. What has passed comes first, most recent first, so the top is
    /// always the latest thing to be filed. Reminders filed ahead of their date (done early) are not
    /// history yet: they follow at the end, soonest first, and move up into the list when their day
    /// comes. Reminders at the same moment are in title order.
    /// </summary>
    public static List<CalendarEvent> ArchiveOrder(IEnumerable<CalendarEvent> archived, DateOnly today)
    {
        var ahead = archived.ToLookup(item => DateOf(item) > today);
        return
        [
            .. ahead[false].OrderByDescending(item => item.Start)
                .ThenBy(item => item.Title, StringComparer.OrdinalIgnoreCase).ThenBy(item => item.Id, StringComparer.Ordinal),
            .. ahead[true].OrderBy(item => item.Start)
                .ThenBy(item => item.Title, StringComparer.OrdinalIgnoreCase).ThenBy(item => item.Id, StringComparer.Ordinal),
        ];
    }

    /// <summary>
    /// Flattens an already-sorted event list into header and event entries. Consecutive events that
    /// share a group go under one header, so the result keeps the order the list came in.
    /// </summary>
    public static List<ListEntry> BuildEntries(IReadOnlyList<CalendarEvent> events, DateOnly today, bool archived)
    {
        var entries = new List<ListEntry>(events.Count + 8);
        var index = 0;
        while (index < events.Count)
        {
            var group = GroupOf(events[index], today, archived);
            var end = index;
            while (end < events.Count && GroupOf(events[end], today, archived) == group) end++;
            entries.Add(new GroupEntry(group, end - index));
            for (var position = index; position < end; position++) entries.Add(new EventEntry(events[position]));
            index = end;
        }
        return entries;
    }

    private static DueGroup GroupOf(CalendarEvent item, DateOnly today, bool archived)
    {
        var date = DateOf(item);
        return archived ? ArchiveGroupFor(date, today) : GroupFor(date, today);
    }

    public static DateOnly DateOf(CalendarEvent item) => DateOnly.FromDateTime(item.Start.LocalDateTime);

    /// <summary>"in 45 min", "in 3 h", "in 5 days", "2 h ago".</summary>
    public static string RelativeDue(DateTimeOffset start, DateTimeOffset now)
    {
        var difference = start - now;
        var minutes = (long)Math.Abs(difference.TotalMinutes);
        if (minutes < 1) return "now";
        var amount = minutes < 90 ? $"{minutes} min"
            : minutes < 48 * 60 ? $"{Math.Round(minutes / 60.0, MidpointRounding.AwayFromZero)} h"
            : $"{Math.Round(minutes / 1440.0, MidpointRounding.AwayFromZero)} days";
        return difference >= TimeSpan.Zero ? $"in {amount}" : $"{amount} ago";
    }

    /// <summary>Every whitespace-separated term must appear somewhere in the reminder's text (case-insensitive).</summary>
    public static bool Matches(CalendarEvent item, string query)
    {
        var terms = Whitespace().Split(query.Trim()).Where(term => term.Length > 0).ToList();
        if (terms.Count == 0) return true;
        var haystack = string.Join("\n", item.Title, item.Location, item.Notes, item.Details, item.CourseCode, item.CourseName,
            CategoryName(item.ResolvedCategory), item.SchoolItemType);
        return terms.All(term => haystack.Contains(term, StringComparison.OrdinalIgnoreCase));
    }

    /// <summary>
    /// Canvas reports cross-listed sections as "CHM110L 4001 CHM110L 4002 CHM110L 4201". When the
    /// label is only one course code repeated with section numbers, show the code once.
    /// </summary>
    public static string CompactCourseLabel(string raw)
    {
        var trimmed = raw.Trim();
        var tokens = Whitespace().Split(trimmed).Where(token => token.Length > 0).ToList();
        if (tokens.Count < 2 || tokens.Count % 2 != 0) return trimmed;
        var code = tokens[0];
        if (!code.Any(char.IsDigit) || !code.Any(char.IsLetter)) return trimmed;
        var repeatsCode = tokens.Where((_, index) => index % 2 == 0).All(token => token.Equals(code, StringComparison.OrdinalIgnoreCase));
        var sectionsOnly = tokens.Where((_, index) => index % 2 == 1).All(token => SectionNumber().IsMatch(token));
        return repeatsCode && sectionsOnly ? code : trimmed;
    }

    /// <summary>"10 points | File upload | 2 attempts", or blank when Canvas sent none of these.</summary>
    public static string AssignmentSummary(CalendarEvent item)
    {
        var parts = new List<string>();
        if (item.PointsPossible is { } points) parts.Add($"{points.ToString("0.##", English)} points");
        if (item.SubmissionTypes.Count > 0) parts.Add(string.Join(", ", item.SubmissionTypes.Select(SubmissionType)));
        if (item.AllowedAttempts is { } attempts)
            parts.Add(attempts < 0 ? "Unlimited attempts" : $"{attempts} attempt{(attempts == 1 ? "" : "s")}");
        return string.Join(" | ", parts);
    }

    public static string SchoolItemType(string? value) => value?.ToLowerInvariant() switch
    {
        "quiz" => "Quiz",
        "discussion" => "Discussion",
        "peer_review" => "Peer review",
        "assignment" => "Assignment",
        _ => "Coursework",
    };

    public static string SubmissionType(string value) => value.ToLowerInvariant() switch
    {
        "online_upload" => "File upload",
        "online_text_entry" => "Text entry",
        "online_quiz" => "Canvas quiz",
        "discussion_topic" => "Discussion",
        "external_tool" => "External tool",
        "media_recording" => "Media recording",
        "on_paper" => "In person",
        "none" => "No submission",
        _ => Capitalize(value.Replace('_', ' ')),
    };

    public static string CategoryName(string category) =>
        category.Equals("automatic", StringComparison.OrdinalIgnoreCase) || category.Equals("auto", StringComparison.OrdinalIgnoreCase)
            ? "General"
            : Capitalize(category.ToLowerInvariant());

    public static string DescribeOffset(int minutes) => minutes switch
    {
        0 => "at event time",
        _ when minutes % 10080 == 0 => $"{minutes / 10080} week{(minutes == 10080 ? "" : "s")} before",
        _ when minutes % 1440 == 0 => $"{minutes / 1440} day{(minutes == 1440 ? "" : "s")} before",
        _ when minutes % 60 == 0 => $"{minutes / 60} hour{(minutes == 60 ? "" : "s")} before",
        _ => $"{minutes} minutes before",
    };

    public static string FormatOffsets(IEnumerable<int> offsets) =>
        string.Join(", ", offsets.Distinct().OrderByDescending(offset => offset).Select(DescribeOffset));

    /// <summary>Heading of a reminder popup: "Tonight: X", "Tomorrow: X", "In 3 days: X", "Now: X".</summary>
    public static string AlertTitle(string kind, string title)
    {
        if (kind == ReminderSchedule.KindTonight) return $"Tonight: {title}";
        if (!kind.StartsWith("offset_", StringComparison.Ordinal) || !int.TryParse(kind.AsSpan(7), out var offset)) return title;
        return offset switch
        {
            0 => $"Now: {title}",
            1440 => $"Tomorrow: {title}",
            _ when offset % 1440 == 0 => $"In {offset / 1440} days: {title}",
            _ when offset % 60 == 0 => $"In {offset / 60} hour{(offset == 60 ? "" : "s")}: {title}",
            _ => $"In {offset} minutes: {title}",
        };
    }

    /// <summary>"Fri, Oct 2", or "Oct 2, 2027" when the year differs from today's.</summary>
    public static string ShortDate(DateOnly date, DateOnly today) =>
        date.ToString(date.Year == today.Year ? "ddd, MMM d" : "MMM d, yyyy", English);

    public static string Time(DateTimeOffset value) => value.ToString("h:mm tt", English);

    public static string FullDateTime(DateTimeOffset value) => value.ToString("dddd, MMMM d, yyyy 'at' h:mm tt", English);

    public static string EventDate(DateTimeOffset value) => value.ToString("ddd, MMM d, h:mm tt", English);

    private static string Capitalize(string value) => value.Length == 0 ? value : char.ToUpperInvariant(value[0]) + value[1..];
}

/// <summary>
/// The rule of a repeating reminder in words: "Every week on Friday at 8:00 AM, until Dec 4, 2026".
/// It mirrors RepeatText in the phone app's RepeatRule.kt; both apps word a rule alike.
/// </summary>
public static class RepeatText
{
    private static readonly CultureInfo English = CultureInfo.InvariantCulture;

    public static string Describe(RepeatRule rule)
    {
        var unit = rule.Freq switch
        {
            "daily" => "day",
            "weekly" => "week",
            "monthly" => "month",
            "yearly" => "year",
            _ => null,
        };
        if (unit is null ||
            !DateOnly.TryParseExact(rule.StartDate, "yyyy-MM-dd", English, DateTimeStyles.None, out var start) ||
            !TimeOnly.TryParseExact(rule.Time, "HH:mm", English, DateTimeStyles.None, out var time))
            return "Repeats";
        var text = new System.Text.StringBuilder("Every ");
        text.Append(rule.Interval <= 1 ? unit : $"{rule.Interval} {unit}s");
        switch (rule.Freq)
        {
            case "weekly":
                var days = rule.Weekdays.Where(day => day is >= 1 and <= 7).Distinct().Order().ToList();
                if (days.Count == 0) days.Add(IsoWeekday(start));
                text.Append(" on ").Append(JoinWords(days.Select(WeekdayName).ToList()));
                break;
            case "monthly":
                text.Append(" on the ").Append(Ordinal(start.Day));
                break;
            case "yearly":
                text.Append(" on ").Append(start.ToString("MMMM d", English));
                break;
        }
        text.Append(" at ").Append(time.ToString("h:mm tt", English));
        if (DateOnly.TryParseExact(rule.Until, "yyyy-MM-dd", English, DateTimeStyles.None, out var until))
            text.Append(", until ").Append(until.ToString("MMM d, yyyy", English));
        else if (rule.Count is { } count)
            text.Append(", ").Append(count.ToString(English)).Append(count == 1 ? " time" : " times");
        return text.ToString();
    }

    /// <summary>1 = Monday to 7 = Sunday.</summary>
    public static string WeekdayName(int isoDay) => ((DayOfWeek)(isoDay % 7)).ToString();

    public static string Ordinal(int day) => day.ToString(English) + ((day % 100) switch
    {
        11 or 12 or 13 => "th",
        _ => (day % 10) switch { 1 => "st", 2 => "nd", 3 => "rd", _ => "th" },
    });

    private static int IsoWeekday(DateOnly date) => date.DayOfWeek == DayOfWeek.Sunday ? 7 : (int)date.DayOfWeek;

    private static string JoinWords(IReadOnlyList<string> words) => words.Count switch
    {
        0 => "",
        1 => words[0],
        _ => string.Join(", ", words.Take(words.Count - 1)) + " and " + words[^1],
    };
}
