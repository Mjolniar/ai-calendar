using System.Collections.Concurrent;
using System.Globalization;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace AICalendar.Desktop;

public sealed class CalendarEvent
{
    public string Id { get; set; } = "";
    public string Title { get; set; } = "";
    public string StartAt { get; set; } = "";
    public string TimeZone { get; set; } = "America/Denver";
    public string Location { get; set; } = "";
    public string Notes { get; set; } = "";
    public bool Critical { get; set; }
    public List<int> ReminderOffsetsMinutes { get; set; } = [1440];
    public bool Done { get; set; }
    /// <summary>The feed the bridge filed it under: "life", "school" or "delivery". Blank when it did not say.</summary>
    public string Domain { get; set; } = "";
    public string Category { get; set; } = "automatic";
    public string Source { get; set; } = "chatgpt";
    public string? SourceUrl { get; set; }
    public string? CourseId { get; set; }
    public string? CourseName { get; set; }
    public string? CourseCode { get; set; }
    public string? CourseColorKey { get; set; }
    public string? SchoolItemType { get; set; }
    public string? Details { get; set; }
    public double? PointsPossible { get; set; }
    public List<string> SubmissionTypes { get; set; } = [];
    public string? UnlockAt { get; set; }
    public string? LockAt { get; set; }
    public int? AllowedAttempts { get; set; }
    public long Revision { get; set; }
    /// <summary>Set on an occurrence of a repeating reminder: the series it belongs to.</summary>
    public string? SeriesId { get; set; }
    /// <summary>The occurrence's date within its series (yyyy-MM-dd). It identifies the occurrence.</summary>
    public string? OccurrenceDate { get; set; }
    /// <summary>An occurrence that was changed on its own ("this one").</summary>
    public bool Detached { get; set; }

    [JsonIgnore]
    public bool IsOccurrence => !string.IsNullOrWhiteSpace(SeriesId);

    private string? _startSource;
    private string? _startZone;
    private DateTimeOffset _startValue;
    private string? _categorySource;
    private string? _categoryValue;
    private string? _birthdaySource;
    private bool _birthdayValue;
    private static readonly Regex BirthdayTitle = new(@"(?<![a-z0-9])(?:birthdays?|b-?days?)(?![a-z0-9])",
        RegexOptions.IgnoreCase | RegexOptions.Compiled | RegexOptions.CultureInvariant);
    private static readonly ConcurrentDictionary<string, TimeZoneInfo> Zones = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>
    /// Start in the PC's local time. The bridge sends UTC ("...Z"); a value without an offset is
    /// interpreted as wall-clock time in the event's IANA zone (e.g. America/Denver), not as UTC.
    /// Cached because sorting, filtering and the month grid read it many times per render.
    /// </summary>
    [JsonIgnore]
    public DateTimeOffset Start
    {
        get
        {
            if (ReferenceEquals(_startSource, StartAt) && ReferenceEquals(_startZone, TimeZone)) return _startValue;
            _startValue = ParseStart(StartAt, TimeZone);
            _startSource = StartAt;
            _startZone = TimeZone;
            return _startValue;
        }
    }

    private static DateTimeOffset ParseStart(string? value, string? zone)
    {
        if (string.IsNullOrWhiteSpace(value) ||
            !DateTime.TryParse(value, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var parsed))
            return DateTimeOffset.MinValue;
        if (parsed.Kind != DateTimeKind.Unspecified)
            return DateTimeOffset.TryParse(value, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var withOffset)
                ? withOffset.ToLocalTime()
                : DateTimeOffset.MinValue;
        var timeZone = ResolveZone(zone);
        try { return new DateTimeOffset(parsed, timeZone.GetUtcOffset(parsed)).ToLocalTime(); }
        catch (ArgumentException) { return DateTimeOffset.MinValue; }
    }

    private static TimeZoneInfo ResolveZone(string? zone)
    {
        if (string.IsNullOrWhiteSpace(zone)) return TimeZoneInfo.Local;
        return Zones.GetOrAdd(zone.Trim(), id =>
        {
            if (TimeZoneInfo.TryFindSystemTimeZoneById(id, out var found)) return found;
            if (TimeZoneInfo.TryConvertIanaIdToWindowsId(id, out var windowsId) &&
                TimeZoneInfo.TryFindSystemTimeZoneById(windowsId, out found)) return found;
            return TimeZoneInfo.Local;
        });
    }

    /// <summary>Replaces nulls from the wire (System.Text.Json does not enforce nullability) with safe defaults.</summary>
    public void Normalize()
    {
        Id = Id?.Trim() ?? "";
        Title ??= "";
        StartAt ??= "";
        TimeZone = string.IsNullOrWhiteSpace(TimeZone) ? "America/Denver" : TimeZone;
        Location ??= "";
        Notes ??= "";
        Domain = Domain?.Trim() ?? "";
        Category ??= "automatic";
        Source ??= "";
        ReminderOffsetsMinutes ??= [];
        SubmissionTypes = SubmissionTypes?.Where(value => value is not null).ToList() ?? [];
    }

    [JsonIgnore]
    public bool IsArchived => IsArchivedAt(DateTimeOffset.Now);

    /// <summary>Done, or no longer current: such a reminder is listed under Archived.</summary>
    public bool IsArchivedAt(DateTimeOffset now) => Done || (Start < now && CurrentUntil < now);

    /// <summary>A birthday, going by its title: "Megan's birthday", "Dad's 60th birthday dinner".</summary>
    [JsonIgnore]
    public bool IsBirthday
    {
        get
        {
            if (ReferenceEquals(_birthdaySource, Title)) return _birthdayValue;
            _birthdaySource = Title;
            return _birthdayValue = BirthdayTitle.IsMatch(Title ?? "");
        }
    }

    /// <summary>
    /// True when the reminder's time is only a stand-in for "that day", so it is kept on the list
    /// until that day is over: a delivery (the time is when it is expected) and a birthday (it lasts
    /// all day).
    /// </summary>
    [JsonIgnore]
    public bool IsListedAllDay => IsDelivery || IsBirthday;

    /// <summary>
    /// The last moment an open reminder counts as current: its own time, or for one that is listed
    /// all day the end of that day, so that it is filed under Archived the day after.
    /// </summary>
    [JsonIgnore]
    public DateTimeOffset CurrentUntil
    {
        get
        {
            var start = Start;
            if (!IsListedAllDay || start == DateTimeOffset.MinValue) return start;
            var nextMidnight = DateTime.SpecifyKind(start.LocalDateTime.Date.AddDays(1), DateTimeKind.Unspecified);
            return new DateTimeOffset(nextMidnight, TimeZoneInfo.Local.GetUtcOffset(nextMidnight)).AddMilliseconds(-1);
        }
    }

    /// <summary>
    /// A package, order or shipment on its way: listed on the Deliveries tab, and it never alerts.
    /// Only the bridge filing it there makes it one (the delivery feed, or the delivery category when
    /// no feed was given). The wording is never used to guess: a wrong guess would silence a real reminder.
    /// </summary>
    [JsonIgnore]
    public bool IsDelivery => Domain.Equals("delivery", StringComparison.OrdinalIgnoreCase) ||
                              (string.IsNullOrWhiteSpace(Domain) && Category.Equals("delivery", StringComparison.OrdinalIgnoreCase));

    /// <summary>The lead times that actually alert: none for a delivery, whatever is stored on it.</summary>
    [JsonIgnore]
    public IReadOnlyList<int> AlertOffsets => IsDelivery ? [] : ReminderOffsetsMinutes;

    [JsonIgnore]
    public string ResolvedDomain => IsDelivery ? "delivery"
        : Domain.Equals("school", StringComparison.OrdinalIgnoreCase) ||
          Category.Equals("school", StringComparison.OrdinalIgnoreCase) ||
          Source.Equals("canvas", StringComparison.OrdinalIgnoreCase) ? "school"
        : "life";

    [JsonIgnore]
    public AssessmentType Assessment => AssessmentClassifier.For(this);

    [JsonIgnore]
    public string CourseLabel => FirstNonBlank(CourseCode, CourseName, "Canvas course");

    [JsonIgnore]
    public string CourseKey => FirstNonBlank(CourseColorKey, CourseId, CourseName, CourseCode, "school");

    [JsonIgnore]
    public bool IsCanvas => Source.Equals("canvas", StringComparison.OrdinalIgnoreCase);

    [JsonIgnore]
    public string ResolvedCategory
    {
        get
        {
            if (!string.IsNullOrWhiteSpace(Category) &&
                !Category.Equals("automatic", StringComparison.OrdinalIgnoreCase) &&
                !Category.Equals("auto", StringComparison.OrdinalIgnoreCase)) return Category;
            // A delivery is filed by choice, and takes its category from that choice too.
            if (IsDelivery) return "delivery";
            var text = $"{Title} {Location} {Notes}";
            if (_categoryValue is not null && string.Equals(_categorySource, text, StringComparison.Ordinal)) return _categoryValue;
            _categorySource = text;
            return _categoryValue = CategoryRules.Classify(text, Title);
        }
    }

    private static string FirstNonBlank(params string?[] values) =>
        values.First(value => !string.IsNullOrWhiteSpace(value))!;
}

/// <summary>
/// Keyword classifier. One precompiled alternation per category; the previous per-word
/// Regex.IsMatch calls (~60 patterns) overflowed the static regex cache and re-parsed
/// patterns for every card, accent brush and calendar cell.
/// </summary>
internal static class CategoryRules
{
    private static readonly (string Category, Regex Pattern)[] Rules =
    [
        ("medical", Words("clinic", "dental", "dentist", "doctor", "health", "hospital", "medical", "medication", "ortho", "pharmacy", "prescription", "therapy", "va clinic", "va video connect", "veterans affairs")),
        ("school", Words("university", "campus", "class", "course", "exam", "homework", "lab kit", "lecture", "quiz", "school", "semester", "student center", "syllabus", "eth 110")),
        ("travel", Words("airport", "boarding", "departure", "flight", "hotel", "itinerary", "rental car", "travel", "trip")),
        ("housing", Words("apartment", "housing", "landlord", "lease", "move-out", "move out", "moving", "movers", "packing", "rent", "tenant", "utility")),
        ("financial", Words("bank", "bill", "budget", "credit", "fee", "financial", "insurance", "invoice", "payment", "refund", "tax", "tuition")),
        ("work", Words("coworker", "deadline", "interview", "job", "meeting", "office", "shift", "work")),
        ("personal", Words("anniversary", "birthday", "family", "personal")),
    ];
    private static readonly Regex TestTitle = new(@"\btest\b", RegexOptions.IgnoreCase | RegexOptions.Compiled);

    public static string Classify(string text, string title)
    {
        foreach (var (category, pattern) in Rules)
        {
            if (pattern.IsMatch(text) || (category == "school" && TestTitle.IsMatch(title))) return category;
        }
        return "general";
    }

    private static Regex Words(params string[] words) => new(
        @"(?<![a-z0-9])(?:" + string.Join("|", words.Select(Regex.Escape)) + @")(?![a-z0-9])",
        RegexOptions.IgnoreCase | RegexOptions.Compiled | RegexOptions.CultureInvariant);
}

public enum AssessmentType { None, Quiz, Test, Exam }

public static class AssessmentClassifier
{
    public static AssessmentType For(CalendarEvent item)
    {
        if (item.SchoolItemType?.Equals("quiz", StringComparison.OrdinalIgnoreCase) == true ||
            item.SubmissionTypes.Any(value => value.Equals("online_quiz", StringComparison.OrdinalIgnoreCase))) return AssessmentType.Quiz;
        if (item.ResolvedDomain != "school") return AssessmentType.None;
        var title = item.Title;
        if (System.Text.RegularExpressions.Regex.IsMatch(title, @"\bquiz(?:zes)?\b", System.Text.RegularExpressions.RegexOptions.IgnoreCase)) return AssessmentType.Quiz;
        if (System.Text.RegularExpressions.Regex.IsMatch(title, @"\b(?:exam(?:ination)?s?|midterms?)\b|\bfinals?\b(?!\s+(?:project|paper|presentation|assignment|draft))", System.Text.RegularExpressions.RegexOptions.IgnoreCase)) return AssessmentType.Exam;
        if (System.Text.RegularExpressions.Regex.IsMatch(title, @"\btests?\b", System.Text.RegularExpressions.RegexOptions.IgnoreCase)) return AssessmentType.Test;
        return AssessmentType.None;
    }
}

public sealed class SyncEnvelope
{
    public long Revision { get; set; }
    public List<CalendarEvent> Events { get; set; } = [];
    /// <summary>The repeating reminders; null when the bridge sent none (an older bridge).</summary>
    public List<ReminderSeries>? Series { get; set; }
    [JsonIgnore]
    public int SkippedEvents { get; set; }
}

/// <summary>When a repeating reminder happens, as the bridge stores it. Dates and the time are local to the series.</summary>
public sealed class RepeatRule
{
    /// <summary>"daily", "weekly", "monthly" or "yearly".</summary>
    public string Freq { get; set; } = "";
    public int Interval { get; set; } = 1;
    /// <summary>ISO weekdays, 1 = Monday to 7 = Sunday. Weekly only.</summary>
    public List<int> Weekdays { get; set; } = [];
    public string StartDate { get; set; } = "";
    /// <summary>HH:mm.</summary>
    public string Time { get; set; } = "";
    public string? Until { get; set; }
    public int? Count { get; set; }
    public string? NotBefore { get; set; }
}

/// <summary>A repeating reminder. Its occurrences are ordinary reminders that carry its id.</summary>
public sealed class ReminderSeries
{
    public string Id { get; set; } = "";
    public string Title { get; set; } = "";
    public string TimeZone { get; set; } = "";
    public RepeatRule Rule { get; set; } = new();
    public List<string> SkippedDates { get; set; } = [];

    /// <summary>Reads each series on its own, so one malformed record cannot hide the rest.</summary>
    public static List<ReminderSeries> ReadAll(JsonElement array)
    {
        var series = new List<ReminderSeries>();
        if (array.ValueKind != JsonValueKind.Array) return series;
        foreach (var element in array.EnumerateArray())
        {
            try
            {
                if (element.Deserialize<ReminderSeries>(EventJson.ReadOptions) is { Id.Length: > 0 } item) series.Add(item);
            }
            catch (Exception exception) when (exception is JsonException or NotSupportedException or InvalidOperationException or FormatException or OverflowException)
            {
            }
        }
        return series;
    }
}

public static class EventJson
{
    public static readonly JsonSerializerOptions ReadOptions = new()
    {
        PropertyNameCaseInsensitive = true,
        NumberHandling = JsonNumberHandling.AllowReadingFromString,
    };

    /// <summary>Deserializes each event independently so one malformed record cannot break the feed.</summary>
    public static List<CalendarEvent> ReadEvents(JsonElement array, out int skipped)
    {
        skipped = 0;
        var events = new List<CalendarEvent>();
        if (array.ValueKind != JsonValueKind.Array) return events;
        var seen = new Dictionary<string, int>(StringComparer.Ordinal);
        foreach (var element in array.EnumerateArray())
        {
            CalendarEvent? item;
            try { item = element.Deserialize<CalendarEvent>(ReadOptions); }
            catch (Exception exception) when (exception is JsonException or NotSupportedException or InvalidOperationException or FormatException or OverflowException)
            {
                skipped++;
                continue;
            }
            if (item is null) { skipped++; continue; }
            item.Normalize();
            if (item.Id.Length == 0) { skipped++; continue; }
            // Duplicate IDs would render twice and fire duplicate reminders; keep the newest revision.
            if (seen.TryGetValue(item.Id, out var index))
            {
                if (item.Revision >= events[index].Revision) events[index] = item;
                continue;
            }
            seen[item.Id] = events.Count;
            events.Add(item);
        }
        return events;
    }
}
