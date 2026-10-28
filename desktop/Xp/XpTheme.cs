using System.Collections;
using System.Windows;

namespace AICalendar.Desktop.Xp;

/// <summary>
/// Puts the XP theme into an application and switches between its two palettes. The application's
/// first merged dictionary holds the palette in use; controls read colors with DynamicResource, so
/// a switch restyles every open window at once.
/// </summary>
public static class XpTheme
{
    private static ResourceDictionary? _luna;
    private static ResourceDictionary? _noir;

    public static bool Dark { get; private set; }

    /// <summary>
    /// Adds the theme to an application's resources: the palette, the icons and the control styles.
    /// Call it before the first window is created.
    /// </summary>
    public static void Install(Application application, bool dark = false)
    {
        _luna = Load("Luna.xaml");
        _noir = Load("Noir.xaml");
        var merged = application.Resources.MergedDictionaries;
        merged.Insert(0, Copy(Palette(dark)));
        merged.Add(Load("XpIcons.xaml"));
        merged.Add(Load("XpControls.xaml"));
        Dark = dark;
    }

    public static void Apply(bool dark)
    {
        Application.Current.Resources.MergedDictionaries[0] = Copy(Palette(dark));
        Dark = dark;
    }

    /// <summary>The colors of one palette, as loaded from Luna.xaml or Noir.xaml.</summary>
    internal static ResourceDictionary Palette(bool dark) =>
        (dark ? _noir : _luna) ?? throw new InvalidOperationException("XpTheme.Install has not run.");

    private static ResourceDictionary Load(string file) => new()
    {
        Source = new Uri($"/{typeof(XpTheme).Assembly.GetName().Name};component/Xp/{file}", UriKind.Relative),
    };

    /// <summary>A copy, so the loaded palettes stay whole however often the one in use is replaced.</summary>
    private static ResourceDictionary Copy(ResourceDictionary source)
    {
        var copy = new ResourceDictionary();
        foreach (DictionaryEntry entry in source) copy[entry.Key] = entry.Value;
        return copy;
    }
}
