using System.Reflection;
using AICalendar.Desktop.Xp;

namespace AICalendar.Desktop;

/// <summary>Help > About: the version and a few facts about the current state.</summary>
public partial class AboutWindow : XpWindow
{
    public AboutWindow(IReadOnlyList<DetailLine> facts)
    {
        InitializeComponent();
        var version = Assembly.GetExecutingAssembly().GetName().Version;
        VersionText.Text = version is null ? "for Windows" : $"Version {version.ToString(3)} for Windows";
        Facts.ItemsSource = facts;
    }
}
