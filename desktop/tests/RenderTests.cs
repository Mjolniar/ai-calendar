using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using AICalendar.Desktop.Xp;

namespace AICalendar.Desktop.Tests;

/// <summary>
/// Draws every window in both palettes and leaves the pictures in tests/artifacts, to look over
/// after a change to the theme. A window that fails to load its resources fails here.
/// </summary>
[Collection(UiCollection.Name)]
public sealed class RenderTests(UiFixture ui)
{
    private static readonly Brush Desktop = new SolidColorBrush(Color.FromRgb(0x3A, 0x6E, 0xA5));

    public static TheoryData<string, bool> Palettes => new() { { "luna", false }, { "noir", true } };

    private static void Click(System.Windows.Controls.Primitives.ButtonBase button) =>
        button.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Primitives.ButtonBase.ClickEvent));

    /// <summary>A control inside the row of an items control that matches.</summary>
    private static T Control<T>(ItemsControl list, Func<object, bool> row) where T : DependencyObject
    {
        UiFixture.Settle();
        var container = list.ItemContainerGenerator.ContainerFromItem(list.Items.Cast<object>().First(row))
            ?? throw new InvalidOperationException("The row is not laid out.");
        return AICalendar.Desktop.Xp.VisualTree.Descendant<T>(container) ?? throw new InvalidOperationException("Not in the row.");
    }

    [Theory]
    [MemberData(nameof(Palettes))]
    public void MainWindowViews(string palette, bool dark) => ui.Run(() =>
    {
        XpTheme.Apply(dark);
        var window = UiFixture.Open(Sample.Store(Sample.WithDeliveries()));
        try
        {
            window.ShowSection("life");
            window.OpenReminder("flight");
            ui.Save(window, $"{palette}-main-life");

            window.ShowSection("delivery");
            window.OpenReminder("lamp");
            ui.Save(window, $"{palette}-main-deliveries");

            window.ShowSection("school");
            window.OpenReminder("lab5");
            ui.Save(window, $"{palette}-main-school");

            window.ShowSection("all");
            ui.Save(window, $"{palette}-main-all");

            window.SetCalendarMode(true);
            ui.Save(window, $"{palette}-main-calendar");
            window.SetCalendarMode(false);

            window.ShowSection("archived");
            window.OpenReminder("quiz3");
            ui.Save(window, $"{palette}-main-archived");

            window.ShowSection("school");
            window.FindBox.Text = "zebra";
            window.ShowNotice("Could not sync: No such host is known. (us-central1-example.cloudfunctions.net:443)");
            window.ShowActionBar("Marked done: Quiz 4: Stoichiometry", () => Task.CompletedTask);
            ui.Save(window, $"{palette}-main-notices");

            window.FindBox.Text = "";
            window.ShowSection("all");
            window.Width = 720;
            window.Height = 480;
            ui.Save(window, $"{palette}-main-small");
        }
        finally { window.Close(); }
    });

    [Theory]
    [MemberData(nameof(Palettes))]
    public void KitchenTab(string palette, bool dark) => ui.Run(() =>
    {
        XpTheme.Apply(dark);
        var bridge = new FakeBridge(Sample.Events()) { Kitchen = KitchenSample.Snapshot() };
        var window = UiFixture.Open(Sample.Store(), () => KitchenSample.Now, bridge);
        var kitchen = window.KitchenPanel;
        try
        {
            window.ShowSection("kitchen");
            kitchen.ShowPart(KitchenView.WeekPartName);
            ui.Save(window, $"{palette}-kitchen-week");

            // A dinner that was cooked and rated, with its note.
            kitchen.SelectDay(KitchenSample.Today.AddDays(-2));
            ui.Save(window, $"{palette}-kitchen-day");

            kitchen.ShowPart(KitchenView.ListPartName);
            window.ShowActionBar("Added to the shopping list: Diced tomatoes", null);
            ui.Save(window, $"{palette}-kitchen-list");

            // On hand: Claude's estimate, with the newest corrections above it.
            kitchen.ShowPart(KitchenView.PantryPartName);
            ui.Save(window, $"{palette}-kitchen-onhand");

            // A row selected, with its three buttons; Something else... started a note about it.
            kitchen.SelectPantryItem("pturkey00004");
            kitchen.StartNoteAbout("pturkey00004");
            kitchen.TellBox.Text += "2 packs in the back of the freezer";
            ui.Save(window, $"{palette}-kitchen-onhand-selected");

            // We're out, with the reply in the bar; all corrections shown.
            _ = kitchen.CorrectAsync("ptomato00002", CorrectionKind.Out);
            Click(Control<Button>(kitchen.OnHandItems, row => row is ShowAllRow));
            ui.Save(window, $"{palette}-kitchen-onhand-corrections");

            // Running low and out only, corrections collapsed again.
            Click(Control<Button>(kitchen.OnHandItems, row => row is ShowAllRow));
            kitchen.ShowLowAndOutOnly(true);
            kitchen.SelectPantryItem(null);
            ui.Save(window, $"{palette}-kitchen-onhand-low");
            kitchen.ShowLowAndOutOnly(false);

            // A refresh that fails keeps what was loaded.
            bridge.Failure = FakeBridge.Offline();
            kitchen.ShowPart(KitchenView.WeekPartName);
            kitchen.SelectDay(KitchenSample.Today);
            _ = kitchen.LoadAsync();
            ui.Save(window, $"{palette}-kitchen-offline");

            bridge.Failure = null;
            _ = kitchen.LoadAsync();
            window.Width = 720;
            window.Height = 480;
            ui.Save(window, $"{palette}-kitchen-small");
            kitchen.ShowPart(KitchenView.ListPartName);
            ui.Save(window, $"{palette}-kitchen-small-list");
            kitchen.ShowPart(KitchenView.PantryPartName);
            ui.Save(window, $"{palette}-kitchen-small-pantry");
        }
        finally { window.Close(); }
    });

    [Theory]
    [MemberData(nameof(Palettes))]
    public void KitchenWithoutAConnectionOrAPlan(string palette, bool dark) => ui.Run(() =>
    {
        XpTheme.Apply(dark);
        var unpaired = UiFixture.Open(Sample.Store(events: [], paired: false), () => KitchenSample.Now);
        try
        {
            unpaired.ShowSection("kitchen");
            ui.Save(unpaired, $"{palette}-kitchen-unpaired");
        }
        finally { unpaired.Close(); }

        var empty = new KitchenSnapshot();
        var window = UiFixture.Open(Sample.Store(), () => KitchenSample.Now, new FakeBridge(Sample.Events()) { Kitchen = empty });
        try
        {
            window.ShowSection("kitchen");
            window.KitchenPanel.ShowPart(KitchenView.WeekPartName);
            ui.Save(window, $"{palette}-kitchen-no-plan");
            window.KitchenPanel.ShowPart(KitchenView.ListPartName);
            ui.Save(window, $"{palette}-kitchen-empty-list");
            window.KitchenPanel.ShowPart(KitchenView.PantryPartName);
            ui.Save(window, $"{palette}-kitchen-onhand-empty");
        }
        finally { window.Close(); }
    });

    [Theory]
    [MemberData(nameof(Palettes))]
    public void MainWindowBeforePairing(string palette, bool dark) => ui.Run(() =>
    {
        XpTheme.Apply(dark);
        var window = UiFixture.Open(Sample.Store(events: [], paired: false));
        try { ui.Save(window, $"{palette}-main-unpaired"); }
        finally { window.Close(); }
    });

    [Theory]
    [MemberData(nameof(Palettes))]
    public void Dialogs(string palette, bool dark) => ui.Run(() =>
    {
        XpTheme.Apply(dark);
        var store = Sample.Store();

        Picture(new ConnectionWindow(store.Load(), store, new ConnectionState(true, false, Sample.Now.AddMinutes(-3), null)), $"{palette}-connection");
        Picture(new ConnectionWindow(new DesktopSettings(), store, new ConnectionState(false, false, null, null)), $"{palette}-connection-new");
        Picture(new OptionsWindow(new ViewSettings { DarkMode = dark }, store), $"{palette}-options");
        Picture(new AboutWindow(
        [
            new DetailLine("Reminders", "14 upcoming, 4 archived"),
            new DetailLine("Cloud", "Connected, last sync Thu, Oct 1, 2:02 PM"),
            new DetailLine("Alerts", "Pop up while AI Planner is running"),
        ]), $"{palette}-about");
        Picture(new XpMessageBox("Delete Reminder", "Permanently delete \"Quiz 4: Stoichiometry\" from every synced device?",
            XpMessageIcon.Warning, "Delete", "Cancel", cancelIsDefault: true), $"{palette}-confirm");
        Picture(new XpMessageBox("AI Planner", "Could not update the reminder.\n\nThe calendar service rejected the change: title is required",
            XpMessageIcon.Error, "OK", null, cancelIsDefault: false), $"{palette}-message");

        void Picture(Window dialog, string name)
        {
            UiFixture.Show(dialog);
            try { ui.Save(dialog, name); }
            finally { dialog.Close(); }
        }
    });

    [Theory]
    [MemberData(nameof(Palettes))]
    public void Balloons(string palette, bool dark) => ui.Run(() =>
    {
        XpTheme.Apply(dark);
        var reminder = new XpBalloon("Tomorrow: Quiz 4: Stoichiometry", "Fri, Oct 2, 11:59 PM\nCHM110 - Quiz", "Icon.Warning", () => { },
            [new BalloonAction("Open", () => { }), new BalloonAction("Mark done", () => { })]);
        var plain = new XpBalloon("AI Planner is still running",
            "Reminders keep popping up here. Double-click the calendar icon in the notification area to open the window.", "Icon.Info", null);
        foreach (var (balloon, name) in new[] { (reminder, "balloon-reminder"), (plain, "balloon-info") })
        {
            UiFixture.Show(balloon);
            try
            {
                // Skip the fade-in.
                balloon.BeginAnimation(UIElement.OpacityProperty, null);
                balloon.Opacity = 1;
                ui.Save(balloon, $"{palette}-{name}", Desktop);
            }
            finally { balloon.Close(); }
        }
    });

    [Theory]
    [MemberData(nameof(Palettes))]
    public void Menus(string palette, bool dark) => ui.Run(() =>
    {
        XpTheme.Apply(dark);
        // A drop-down outside its popup: the menu is laid out on its own and never shown on screen.
        var menu = new ContextMenu();
        menu.Items.Add(new MenuItem { Header = "Open AI Planner", FontWeight = FontWeights.Bold });
        menu.Items.Add(new MenuItem { Header = "_Sync Now", InputGestureText = "F5", Icon = new Image { Source = (ImageSource)Application.Current.FindResource("Icon.Sync") } });
        menu.Items.Add(new MenuItem { Header = "_List", Tag = "Radio", IsChecked = true });
        menu.Items.Add(new MenuItem { Header = "_Calendar", Tag = "Radio" });
        menu.Items.Add(new MenuItem { Header = "Checked", IsChecked = true });
        menu.Items.Add(new MenuItem { Header = "Not available", IsEnabled = false });
        menu.Items.Add(new Separator());
        menu.Items.Add(new MenuItem { Header = "E_xit" });
        menu.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
        menu.Arrange(new Rect(menu.DesiredSize));
        menu.UpdateLayout();
        ui.Save(menu, $"{palette}-menu", Desktop);
    });
}
