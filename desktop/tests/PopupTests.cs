using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using AICalendar.Desktop.Xp;

namespace AICalendar.Desktop.Tests;

/// <summary>
/// The parts that live in popups. A popup is a window of its own, which Windows keeps on a visible
/// screen wherever its owner is; these tests open one, lay it out and close it again without
/// letting anything draw in between, so nothing shows.
/// </summary>
[Collection(UiCollection.Name)]
public sealed class PopupTests(UiFixture ui)
{
    private static readonly Brush Desktop = new SolidColorBrush(Color.FromRgb(0x3A, 0x6E, 0xA5));

    public static TheoryData<string, bool> Palettes => new() { { "luna", false }, { "noir", true } };

    [Theory]
    [MemberData(nameof(Palettes))]
    public void MenusDropDownWithTheirCommands(string palette, bool dark) => ui.Run(() =>
    {
        XpTheme.Apply(dark);
        var window = UiFixture.Open(Sample.Store());
        try
        {
            var menu = VisualTree.Descendant<Menu>(window)!;
            foreach (var (header, entries) in new[] { ("_View", 4), ("_Tools", 5) })
            {
                var item = menu.Items.OfType<MenuItem>().Single(candidate => (string)candidate.Header == header);
                item.IsSubmenuOpen = true;
                try
                {
                    var popup = (Popup)item.Template.FindName("PART_Popup", item);
                    Assert.True(popup.IsOpen);
                    var content = (FrameworkElement)popup.Child;
                    content.UpdateLayout();
                    Assert.Equal(entries, item.Items.Count);
                    // Each command is laid out with a real height: the drop-down is not an empty frame.
                    Assert.True(content.ActualHeight > 24 * item.Items.OfType<MenuItem>().Count());
                    ui.Save(content, $"{palette}-menu{header.Replace("_", "-").ToLowerInvariant()}", Desktop, settle: false);
                }
                finally { item.IsSubmenuOpen = false; }
            }
        }
        finally { window.Close(); }
    });

    [Theory]
    [MemberData(nameof(Palettes))]
    public void ClassFilterDropsDownWithEveryClass(string palette, bool dark) => ui.Run(() =>
    {
        XpTheme.Apply(dark);
        var window = UiFixture.Open(Sample.Store());
        try
        {
            window.ShowSection("school");
            UiFixture.Settle();
            window.CourseFilter.IsDropDownOpen = true;
            try
            {
                var popup = (Popup)window.CourseFilter.Template.FindName("PART_Popup", window.CourseFilter);
                Assert.True(popup.IsOpen);
                var content = (FrameworkElement)popup.Child;
                content.UpdateLayout();
                Assert.True(content.ActualHeight > 7 * 18);
                ui.Save(content, $"{palette}-classes", Desktop, settle: false);
            }
            finally { window.CourseFilter.IsDropDownOpen = false; }
        }
        finally { window.Close(); }
    });

    [Theory]
    [MemberData(nameof(Palettes))]
    public void TrayMenuOffersOpenSyncAndExit(string palette, bool dark) => ui.Run(() =>
    {
        XpTheme.Apply(dark);
        var window = UiFixture.Open(Sample.Store());
        try
        {
            var menu = window.BuildTrayMenu();
            Assert.Equal(["Open AI Planner", "Sync Now", "Exit"], menu.Items.OfType<MenuItem>().Select(item => (string)item.Header));
            // Laid out on its own, never opened: its place would be at the mouse pointer.
            menu.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
            menu.Arrange(new Rect(menu.DesiredSize));
            menu.UpdateLayout();
            ui.Save(menu, $"{palette}-menu-tray", Desktop, settle: false);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void BalloonsStackUpwardsAndTheOldestGivesWay() => ui.Run(() =>
    {
        XpTheme.Apply(false);
        var area = new Rect(-30000, -30000, 1280, 800);
        var realArea = XpBalloon.Area;
        XpBalloon.Area = () => area;
        try
        {
            foreach (var name in new[] { "One", "Two", "Three", "Four", "Five" }) XpBalloon.Show(name, "Fri, Oct 2, 11:59 PM");
            UiFixture.Settle();
            // Four at most: the first has made room.
            Assert.Equal(["Two", "Three", "Four", "Five"], XpBalloon.Showing.Select(balloon => balloon.Heading.Text));

            // Windows places windows on whole pixels, so positions can be off by a fraction.
            var bottom = area.Bottom - 2;
            foreach (var balloon in XpBalloon.Showing)
            {
                Assert.InRange(balloon.Left, area.Right - balloon.Width - 5, area.Right - balloon.Width - 3);
                Assert.InRange(balloon.Top + balloon.ActualHeight, bottom - 1, bottom + 1);
                bottom = balloon.Top;
            }
            // Only the lowest one points at the notification area.
            Assert.Equal([Visibility.Visible, Visibility.Collapsed, Visibility.Collapsed, Visibility.Collapsed],
                XpBalloon.Showing.Select(balloon => balloon.Tail.Visibility));

            XpBalloon.Showing[0].Close();
            UiFixture.Settle();
            Assert.Equal(["Three", "Four", "Five"], XpBalloon.Showing.Select(balloon => balloon.Heading.Text));
            Assert.Equal(Visibility.Visible, XpBalloon.Showing[0].Tail.Visibility);
            Assert.InRange(XpBalloon.Showing[0].Top + XpBalloon.Showing[0].ActualHeight, area.Bottom - 3, area.Bottom - 1);
        }
        finally
        {
            XpBalloon.CloseAll();
            XpBalloon.Area = realArea;
        }
        Assert.Empty(XpBalloon.Showing);
    });
}
