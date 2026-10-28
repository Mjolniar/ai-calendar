using System.Windows;
using System.Windows.Media;

namespace AICalendar.Desktop.Tests;

/// <summary>
/// The palettes themselves: every key defined once, both palettes complete, and text readable in
/// both (WCAG contrast of 4.5:1 for text, 7:1 for the main ink), as XpPaletteTest.kt checks on the phone.
/// </summary>
[Collection(UiCollection.Name)]
public sealed class ThemeTests(UiFixture ui)
{
    private static readonly string[] Palettes = ["Luna", "Noir"];

    private static ResourceDictionary Palette(string name) => Xp.XpTheme.Palette(dark: name == "Noir");

    private static double Luminance(Color color)
    {
        static double Channel(byte value)
        {
            var v = value / 255.0;
            return v <= 0.03928 ? v / 12.92 : Math.Pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * Channel(color.R) + 0.7152 * Channel(color.G) + 0.0722 * Channel(color.B);
    }

    private static void Check(string palette, string ink, string paper, double minimum)
    {
        var colors = Palette(palette);
        var first = Luminance(((SolidColorBrush)colors[ink]).Color);
        var second = Luminance(((SolidColorBrush)colors[paper]).Color);
        var ratio = (Math.Max(first, second) + 0.05) / (Math.Min(first, second) + 0.05);
        Assert.True(ratio >= minimum, $"{palette}: {ink} on {paper} is {ratio:0.00}:1, needs {minimum}:1");
    }

    [Fact]
    public void TheTestsNeverStartTheRealApp() => ui.Run(() =>
    {
        UiFixture.Settle();
        // The app's own startup would have left its main window open: over the real settings
        // folder, with the tray icon and the timers.
        Assert.DoesNotContain(Application.Current.Windows.OfType<MainWindow>(), window => window.RunsInBackground);
    });

    [Fact]
    public void EveryResourceKeyIsDefinedOnce() => ui.Run(() =>
    {
        // A style and a color under one key: whichever dictionary comes last wins, for both uses.
        var repeated = Application.Current.Resources.MergedDictionaries
            .SelectMany(dictionary => dictionary.Keys.Cast<object>())
            .GroupBy(key => key)
            .Where(group => group.Count() > 1)
            .Select(group => group.Key.ToString());
        Assert.Empty(repeated);
    });

    [Fact]
    public void SwitchingThePaletteRestylesAnOpenWindow() => ui.Run(() =>
    {
        Xp.XpTheme.Apply(false);
        var window = UiFixture.Open(Sample.Store());
        try
        {
            Assert.Equal(Colors.White, ((SolidColorBrush)window.ReminderList.Background).Color);
            Assert.Equal(Colors.Black, ((SolidColorBrush)window.CountText.Foreground).Color);

            Xp.XpTheme.Apply(true);
            UiFixture.Settle();
            Assert.True(Xp.XpTheme.Dark);
            Assert.Equal(((SolidColorBrush)Palette("Noir")["Xp.Window"]).Color, ((SolidColorBrush)window.ReminderList.Background).Color);
            Assert.Equal(((SolidColorBrush)Palette("Noir")["Xp.Text"]).Color, ((SolidColorBrush)window.CountText.Foreground).Color);
            Assert.Equal(((SolidColorBrush)Palette("Noir")["Xp.Face"]).Color, ((SolidColorBrush)window.Background).Color);
        }
        finally
        {
            window.Close();
            Xp.XpTheme.Apply(false);
        }
    });

    [Fact]
    public void BothPalettesDefineTheSameColors() => ui.Run(() =>
    {
        var luna = Palette("Luna").Keys.Cast<string>().Order().ToArray();
        var noir = Palette("Noir").Keys.Cast<string>().Order().ToArray();
        Assert.NotEmpty(luna);
        Assert.Equal(luna, noir);
    });

    [Fact]
    public void MainTextIsHighContrastOnEverySurface() => ui.Run(() =>
    {
        foreach (var palette in Palettes)
        {
            Check(palette, "Xp.Text", "Xp.Face", 7);
            Check(palette, "Xp.Text", "Xp.FaceLight", 7);
            Check(palette, "Xp.Text", "Xp.Window", 7);
            Check(palette, "Xp.Text", "Xp.RowHover", 7);
            Check(palette, "Xp.Text", "Xp.Menu", 7);
            Check(palette, "Xp.TooltipText", "Xp.Tooltip", 7);
        }
    });

    [Fact]
    public void SecondaryAndColoredTextStaysReadable() => ui.Run(() =>
    {
        foreach (var palette in Palettes)
        {
            Check(palette, "Xp.TextDim", "Xp.Face", 4.5);
            Check(palette, "Xp.TextDim", "Xp.FaceLight", 4.5);
            Check(palette, "Xp.TextDim", "Xp.Window", 4.5);
            Check(palette, "Xp.TextDim", "Xp.RowHover", 4.5);
            Check(palette, "Xp.TextDim", "Xp.Tooltip", 4.5);
            Check(palette, "Xp.Link", "Xp.Window", 4.5);
            Check(palette, "Xp.Link", "Xp.Face", 4.5);
            Check(palette, "Xp.Link", "Xp.Tooltip", 4.5);
            Check(palette, "Xp.GroupText", "Xp.Face", 4.5);
            Check(palette, "Xp.GroupText", "Xp.Window", 4.5);
            Check(palette, "Xp.HeaderText", "Xp.Window", 4.5);
            Check(palette, "Xp.ErrorText", "Xp.Face", 4.5);
            // The On hand chips: Probably out is error-colored text on the window, Have a green edge.
            Check(palette, "Xp.ErrorText", "Xp.Window", 4.5);
            Check(palette, "Xp.TagText", "Xp.TagFill", 4.5);
            Check(palette, "Xp.HighlightText", "Xp.Highlight", 4.5);
        }
    });

    [Fact]
    public void ControlEdgesAreVisible() => ui.Run(() =>
    {
        foreach (var palette in Palettes)
        {
            Check(palette, "Xp.FieldBorder", "Xp.Face", 2);
            Check(palette, "Xp.FieldBorder", "Xp.Window", 2);
            Check(palette, "Xp.CheckBorder", "Xp.Window", 3);
            Check(palette, "Xp.Today", "Xp.Window", 3);
            Check(palette, "Xp.CheckGreen", "Xp.Window", 3);
            Check(palette, "Xp.MenuBorder", "Xp.Window", 1.5);
        }
    });
}
