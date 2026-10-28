using System.Collections.Concurrent;
using System.Windows.Media;

namespace AICalendar.Desktop;

/// <summary>
/// The color that identifies a reminder at a glance: its class for Canvas work, otherwise its
/// category. Same colors and the same course hash as the phone app, so a class is one color everywhere.
/// </summary>
public static class EventColors
{
    private static readonly ConcurrentDictionary<string, SolidColorBrush> Brushes = new(StringComparer.OrdinalIgnoreCase);
    private static readonly string[] CourseColors =
        ["#2F7FD9", "#D08A00", "#2E9E5B", "#8757D6", "#D4558C", "#1E9C8E", "#4F6FD8", "#9C8F1C"];
    private static readonly Dictionary<string, string> Categories = new(StringComparer.OrdinalIgnoreCase)
    {
        ["school"] = "#4F8EF7", ["medical"] = "#F06A6A", ["financial"] = "#4CAF7D",
        ["housing"] = "#F0A44B", ["travel"] = "#40B7C4", ["work"] = "#A77BD8",
        ["personal"] = "#D77FA1", ["delivery"] = "#A9744F", ["general"] = "#8D96A2", ["automatic"] = "#8D96A2",
    };

    /// <summary>Marks quizzes, tests and exams on the calendar.</summary>
    public static readonly SolidColorBrush Assessment = Brush("#E68B2C");

    public static SolidColorBrush ForEvent(CalendarEvent item) => item.IsCanvas
        ? ForCourse(item.CourseKey)
        : Brush(Categories.GetValueOrDefault(item.ResolvedCategory, "#8D96A2"));

    public static SolidColorBrush ForCourse(string courseKey)
    {
        // The phone's hash: h = h * 31 + char, in wrapping 32-bit arithmetic.
        var hash = 0;
        foreach (var character in courseKey) hash = unchecked(hash * 31 + character);
        var index = ((hash % CourseColors.Length) + CourseColors.Length) % CourseColors.Length;
        return Brush(CourseColors[index]);
    }

    /// <summary>Shared, frozen brush per color.</summary>
    public static SolidColorBrush Brush(string hex) => Brushes.GetOrAdd(hex, value =>
    {
        var brush = new SolidColorBrush((Color)ColorConverter.ConvertFromString(value));
        brush.Freeze();
        return brush;
    });
}
