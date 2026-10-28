using System.Globalization;
using System.IO;

namespace AICalendar.Desktop.Tests;

/// <summary>Made-up reminders and a throwaway settings folder for the window tests.</summary>
internal static class Sample
{
    /// <summary>Thursday afternoon. The week ends Sunday, October 4.</summary>
    public static readonly DateTimeOffset Now = Local(2026, 10, 1, 14, 5);

    public static readonly string TempRoot = Path.Combine(Path.GetTempPath(), "ai-calendar-desktop-tests");

    public static DateTimeOffset Local(int year, int month, int day, int hour, int minute)
    {
        var wall = new DateTime(year, month, day, hour, minute, 0, DateTimeKind.Unspecified);
        return new DateTimeOffset(wall, TimeZoneInfo.Local.GetUtcOffset(wall));
    }

    /// <summary>A store in its own empty folder, optionally paired, holding <paramref name="events"/> as the cached copy.</summary>
    public static SettingsStore Store(IEnumerable<CalendarEvent>? events = null, bool paired = true, ViewSettings? view = null)
    {
        var store = new SettingsStore(Path.Combine(TempRoot, Guid.NewGuid().ToString("N")));
        if (paired) store.Save(new DesktopSettings { DeviceToken = "not-a-real-token" });
        store.SaveEvents(events ?? Events());
        if (view is not null) store.SaveView(view);
        return store;
    }

    public static CalendarEvent Plain(string id, string title, DateTimeOffset start, string location = "", string notes = "",
        bool critical = false, bool done = false, params int[] offsets) => new()
    {
        Id = id,
        Title = title,
        StartAt = Utc(start),
        Location = location,
        Notes = notes,
        Critical = critical,
        Done = done,
        ReminderOffsetsMinutes = offsets.Length == 0 ? [1440] : [.. offsets],
        Revision = 1,
    };

    public static CalendarEvent Canvas(string id, string title, DateTimeOffset start, string courseCode, string courseName,
        string type = "assignment", double? points = null, string submission = "online_upload", string? details = null,
        bool done = false, int? attempts = null, params int[] offsets) => new()
    {
        Id = id,
        Title = title,
        StartAt = Utc(start),
        Done = done,
        Domain = "school",
        Category = "school",
        Source = "canvas",
        SourceUrl = $"https://canvas.example.edu/courses/{courseCode.Split(' ')[0]}/assignments/{id}",
        CourseId = courseCode.Split(' ')[0],
        CourseCode = courseCode,
        CourseName = courseName,
        SchoolItemType = type,
        PointsPossible = points,
        SubmissionTypes = [submission],
        AllowedAttempts = attempts,
        Details = details,
        ReminderOffsetsMinutes = offsets.Length == 0 ? [1440] : [.. offsets],
        Revision = 1,
    };

    /// <summary>Five upcoming Life reminders, nine upcoming School ones, and four that are archived.</summary>
    public static List<CalendarEvent> Events() =>
    [
        Plain("dentist", "Dentist cleaning", Local(2026, 10, 1, 16, 30), "Family Dental", offsets: [1440, 60]),
        Plain("rent", "Rent due", Local(2026, 10, 2, 9, 0), notes: "Pay through the tenant portal.", critical: true, offsets: [1440, 0]),
        Plain("flight", "Flight to Seattle", Local(2026, 10, 5, 6, 15), "City Airport, gate B12", "Check in online the day before.", offsets: [1440, 180, 0]),
        Plain("oil", "Oil change", Local(2026, 10, 14, 10, 0), "Quick Lane"),
        Plain("registration", "Renew car registration", Local(2026, 11, 12, 9, 0)),

        Canvas("lab5", "Lab 5 Report: Acid-Base Titration", Local(2026, 10, 1, 23, 59), "CHM110L 4001 CHM110L 4002", "General Chemistry I Lab",
            points: 25, details: "Submit one PDF with your data table, the titration curve and the answers to questions 1 to 6.\n\nShow the calculation of the unknown concentration."),
        Canvas("quiz4", "Quiz 4: Stoichiometry", Local(2026, 10, 2, 23, 59), "CHM110", "General Chemistry I", "quiz", 10, "online_quiz",
            attempts: 2, offsets: [4320, 1440, 120, 60, 0]),
        Canvas("discussion", "Discussion: Public Health Ethics", Local(2026, 10, 3, 23, 59), "NUTR150 4002 NUTR150 4006", "Nutrition", "discussion", 15, "discussion_topic"),
        Canvas("pset6", "Problem Set 6", Local(2026, 10, 6, 23, 59), "MATH143 001", "College Algebra", points: 20),
        Canvas("midterm", "Midterm Exam", Local(2026, 10, 8, 13, 0), "MATH143 001", "College Algebra", points: 100, submission: "on_paper",
            offsets: [10080, 1440, 120, 60, 0]),
        Canvas("essay2", "Essay 2 Draft", Local(2026, 10, 9, 23, 59), "ENGL102", "Writing and Rhetoric II", points: 50),
        Canvas("reading", "Reading Response 5", Local(2026, 10, 13, 23, 59), "ENGL102", "Writing and Rhetoric II", submission: "online_text_entry"),
        Canvas("lab6", "Lab 6 Pre-lab", Local(2026, 10, 15, 8, 0), "CHM110L 4001 CHM110L 4002", "General Chemistry I Lab", points: 5),
        Canvas("proposal", "Final Project Proposal", Local(2026, 11, 6, 23, 59), "ETH110 001", "Foundations of Ethics", points: 40),

        Canvas("quiz3", "Quiz 3: The Mole", Local(2026, 9, 25, 23, 59), "CHM110", "General Chemistry I", "quiz", 10, "online_quiz", done: true),
        Canvas("lab4", "Lab 4 Report", Local(2026, 9, 24, 23, 59), "CHM110L 4001 CHM110L 4002", "General Chemistry I Lab", points: 25),
        Plain("insurance", "Car insurance payment", Local(2026, 9, 28, 9, 0), done: true),
        Plain("haircut", "Haircut", Local(2026, 8, 30, 11, 0), "Main Street Barbers"),
    ];

    /// <summary>A package on its way, as the bridge sends one: in the delivery feed, with no alert lead times.</summary>
    public static CalendarEvent Delivery(string id, string title, DateTimeOffset start, string carrier = "", string notes = "",
        bool done = false, params int[] offsets) => new()
    {
        Id = id,
        Title = title,
        StartAt = Utc(start),
        Location = carrier,
        Notes = notes,
        Done = done,
        Domain = "delivery",
        ReminderOffsetsMinutes = [.. offsets],
        Revision = 1,
    };

    /// <summary>
    /// Three deliveries still expected and one from earlier in the week. The first one's time has
    /// passed (9 AM today, and the clock says 2:05 PM), but not its day.
    /// </summary>
    public static List<CalendarEvent> Deliveries() =>
    [
        Delivery("lamp", "Desk lamp", Local(2026, 10, 1, 9, 0), "UPS", "Order 114-2291. Leave at the side door."),
        Delivery("textbook", "Textbook: Chemistry in Context", Local(2026, 10, 3, 9, 0), "USPS"),
        Delivery("jacket", "Winter jacket", Local(2026, 10, 7, 9, 0), "FedEx"),
        Delivery("cable", "USB-C cable", Local(2026, 9, 29, 9, 0), "Amazon"),
    ];

    public static List<CalendarEvent> WithDeliveries() => [.. Events(), .. Deliveries()];

    private static string Utc(DateTimeOffset local) =>
        local.UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'", CultureInfo.InvariantCulture);
}
