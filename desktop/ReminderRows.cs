using System.ComponentModel;
using System.Globalization;
using System.Windows.Media;

namespace AICalendar.Desktop;

/// <summary>One labeled line in a reminder's details ("When", "Class", "Alerts").</summary>
public sealed record DetailLine(string Label, string Value);

/// <summary>Caption of a list group: "Today (2)".</summary>
public sealed class GroupHeaderRow(string label, int count)
{
    public string Label { get; } = label;
    public string CountText { get; } = $"({count})";
}

/// <summary>
/// What one reminder row shows. Everything is worked out here, so the row template only binds.
/// </summary>
public sealed class ReminderRow : INotifyPropertyChanged
{
    private readonly bool _archivedView;
    private readonly bool _showDate;
    private readonly Shown _shown;

    private readonly string? _repeatText;

    public ReminderRow(CalendarEvent item, DateTimeOffset now, DateOnly today, bool archivedView, bool showDate, string? repeatText = null)
    {
        Event = item;
        _archivedView = archivedView;
        _showDate = showDate;
        _repeatText = repeatText;
        _shown = new Shown(item.Id, item.Revision, item.Done, item.StartAt, item.Title, item.Notes, item.Location, item.Details, item.Critical,
            item.ResolvedDomain, item.ResolvedCategory, repeatText, item.Detached);
        Accent = EventColors.ForEvent(item);
        Tag = item.Assessment == AssessmentType.None ? null : item.Assessment.ToString().ToUpperInvariant();
        Course = item.IsCanvas ? ReminderText.CompactCourseLabel(item.CourseLabel) : null;
        var kind = item.IsCanvas ? ReminderText.SchoolItemType(item.SchoolItemType) : ReminderText.CategoryName(item.ResolvedCategory);
        Secondary = string.IsNullOrWhiteSpace(item.Location) ? kind : $"{kind} - {item.Location.Trim()}";
        var body = item.IsCanvas && !string.IsNullOrWhiteSpace(item.Details) ? item.Details : item.Notes;
        Body = body?.Trim() ?? "";
        CanOpenLink = item.IsCanvas && item.SourceUrl?.StartsWith("https://", StringComparison.OrdinalIgnoreCase) == true;
        Refresh(now, today);
    }

    public CalendarEvent Event { get; }
    public Brush Accent { get; }
    public string Title => Event.Title;
    public bool IsDone => Event.Done;
    public bool IsCritical => Event.Critical;
    /// <summary>An occurrence of a repeating reminder: the row shows the repeat icon.</summary>
    public bool IsRepeating => Event.IsOccurrence;

    /// <summary>"QUIZ", "TEST" or "EXAM"; null for everything else.</summary>
    public string? Tag { get; }
    public bool HasTag => Tag is not null;

    /// <summary>Course code for Canvas work; null otherwise.</summary>
    public string? Course { get; }
    public bool HasCaption => Tag is not null || Course is not null;

    public string Secondary { get; }
    public string PrimaryWhen { get; private set; } = "";
    public string SecondaryWhen { get; private set; } = "";
    public IReadOnlyList<DetailLine> Details { get; private set; } = [];
    public string Body { get; }
    public bool HasBody => Body.Length > 0;
    public bool CanOpenLink { get; }
    public string DoneLabel => Event.Done ? "Not done" : "Done";
    public string DoneToolTip => Event.Done ? "Mark not done" : "Mark done";

    public event PropertyChangedEventHandler? PropertyChanged;

    /// <summary>
    /// True when <paramref name="other"/>, built from the current data, would look the same as this
    /// row. The list then keeps this row (and its scroll position and selection) instead of rebinding.
    /// </summary>
    public bool Shows(ReminderRow other) =>
        _shown == other._shown && _archivedView == other._archivedView && _showDate == other._showDate;

    /// <summary>Recomputes the parts that depend on the clock ("in 3 h") without rebuilding the row.</summary>
    public void Refresh(DateTimeOffset now, DateOnly today)
    {
        var date = ReminderText.DateOf(Event);
        var time = ReminderText.Time(Event.Start);
        string primary, secondary;
        if (_archivedView)
        {
            primary = ReminderText.ShortDate(date, today);
            secondary = time;
        }
        else if (!_showDate || date == today)
        {
            primary = time;
            // A delivery or a birthday stays listed all day; "3 h ago" would read as if it had been missed.
            secondary = date != today || (Event.Start < now && Event.IsListedAllDay) ? "" : ReminderText.RelativeDue(Event.Start, now);
        }
        else
        {
            primary = ReminderText.ShortDate(date, today);
            secondary = time;
        }
        var details = BuildDetails(now);
        if (primary != PrimaryWhen)
        {
            PrimaryWhen = primary;
            Changed(nameof(PrimaryWhen));
        }
        if (secondary != SecondaryWhen)
        {
            SecondaryWhen = secondary;
            Changed(nameof(SecondaryWhen));
        }
        if (!details.SequenceEqual(Details))
        {
            Details = details;
            Changed(nameof(Details));
        }
    }

    /// <summary>Puts the check box back in step with the reminder after a change that did not go through.</summary>
    public void RefreshDone()
    {
        Changed(nameof(IsDone));
        Changed(nameof(DoneLabel));
        Changed(nameof(DoneToolTip));
    }

    private void Changed(string property) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(property));

    private List<DetailLine> BuildDetails(DateTimeOffset now)
    {
        var item = Event;
        var upcoming = !item.Done && item.Start >= now;
        var lines = new List<DetailLine>
        {
            new("When", ReminderText.FullDateTime(item.Start) + (upcoming ? $" ({ReminderText.RelativeDue(item.Start, now)})" : "")),
        };
        if (!string.IsNullOrWhiteSpace(item.Location)) lines.Add(new DetailLine("Where", item.Location.Trim()));
        if (item.IsOccurrence)
            lines.Add(new DetailLine("Repeats", (_repeatText ?? "Yes") + (item.Detached ? ". This one was changed on its own." : "")));
        if (item.IsCanvas)
        {
            var course = string.IsNullOrWhiteSpace(item.CourseName) ? ReminderText.CompactCourseLabel(item.CourseLabel) : item.CourseName.Trim();
            lines.Add(new DetailLine("Class", $"{course} - {ReminderText.SchoolItemType(item.SchoolItemType)}"));
            var work = ReminderText.AssignmentSummary(item);
            if (work.Length > 0) lines.Add(new DetailLine("Work", work));
            if (TryCanvasDate(item.UnlockAt, out var opens)) lines.Add(new DetailLine("Opens", opens));
            if (TryCanvasDate(item.LockAt, out var locks)) lines.Add(new DetailLine("Locks", locks));
        }
        lines.Add(new DetailLine("Type", $"{ReminderText.CategoryName(item.ResolvedCategory)} - {(item.IsCanvas ? "Canvas" : "Cloud")}"));
        var alerts = item.IsDelivery ? "None. Deliveries never alert."
            : item.ReminderOffsetsMinutes.Count == 0 ? "None"
            : Capitalize(ReminderText.FormatOffsets(item.ReminderOffsetsMinutes));
        lines.Add(new DetailLine("Alerts", alerts));
        if (upcoming && ReminderSchedule.MovedNote(item.Start, item.AlertOffsets) is { } note) lines.Add(new DetailLine("", note));
        return lines;
    }

    private static bool TryCanvasDate(string? value, out string text)
    {
        text = "";
        if (!DateTimeOffset.TryParse(value, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var parsed)) return false;
        text = ReminderText.EventDate(parsed.ToLocalTime());
        return true;
    }

    private static string Capitalize(string value) => value.Length == 0 ? value : char.ToUpperInvariant(value[0]) + value[1..];

    /// <summary>The fields a row displays, as they were when the row was built.</summary>
    private sealed record Shown(
        string Id, long Revision, bool Done, string StartAt, string Title, string Notes, string Location, string? Details, bool Critical,
        string Feed, string Category, string? Repeat, bool Detached);
}

/// <summary>One cell of the month grid.</summary>
public sealed class DayCell
{
    /// <summary>Null for the blank cells before the first and after the last day of the month.</summary>
    public DateTime? Date { get; init; }
    public string Number => Date?.Day.ToString(CultureInfo.InvariantCulture) ?? "";
    public bool IsDay => Date is not null;
    public bool IsToday { get; init; }
    public bool IsSelected { get; init; }
    public bool HasAssessment { get; init; }
    public IReadOnlyList<Brush> Dots { get; init; } = [];
    public string? ToolTip { get; init; }
}

/// <summary>One entry of the class filter. A null key is "All classes".</summary>
public sealed record CourseChoice(string? Key, string Label, Brush? Color)
{
    public bool HasColor => Color is not null;
}
