using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Documents;
using System.Windows.Media;

namespace AICalendar.Desktop.Tests;

/// <summary>The Kitchen tab against a stand-in for the calendar service: what it shows and the edits it sends.</summary>
[Collection(UiCollection.Name)]
public sealed class KitchenWindowTests(UiFixture ui)
{
    private static readonly DateOnly Thursday = KitchenSample.Today;

    /// <summary>The main window at Thursday 5:30 PM, on the Kitchen tab, with the sample kitchen.</summary>
    private static (MainWindow Window, FakeBridge Bridge) OpenKitchen(SettingsStore? store = null, string part = KitchenView.WeekPartName)
    {
        var bridge = new FakeBridge(Sample.Events()) { Kitchen = KitchenSample.Snapshot() };
        var window = UiFixture.Open(store ?? Sample.Store(), () => KitchenSample.Now, bridge);
        window.ShowSection("kitchen");
        window.KitchenPanel.ShowPart(part);
        UiFixture.Settle();
        return (window, bridge);
    }

    private static string[] ListRows(MainWindow window) => window.KitchenPanel.ShoppingItems.Items.Cast<object>().Select(row => row switch
    {
        KitchenHeaderRow caption => $"{caption.Label} {caption.Detail}".Split(" · ")[0],
        ShoppingRow line => line.Name,
        _ => "?",
    }).ToArray();

    private static ShoppingRow Row(KitchenView panel, string name) => panel.ShoppingItems.Items.OfType<ShoppingRow>().Single(row => row.Name == name);

    private static string[] DayRows(MainWindow window) => window.KitchenPanel.DayList.Items.Cast<object>().Select(row => row switch
    {
        KitchenHeaderRow caption => caption.Label,
        MealDayRow day => $"{day.DayName} {day.Dinner}",
        MealBoxRow => "(box)",
        _ => "?",
    }).ToArray();

    private static T Control<T>(ItemsControl list, Func<object, bool> row, Func<T, bool>? which = null) where T : DependencyObject
    {
        UiFixture.Settle();
        var item = list.Items.Cast<object>().First(row);
        var container = list.ItemContainerGenerator.ContainerFromItem(item) ?? throw new InvalidOperationException("The row is not laid out.");
        return Descendants<T>(container).First(which ?? (_ => true));
    }

    private static IEnumerable<T> Descendants<T>(DependencyObject parent) where T : DependencyObject
    {
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(parent); index++)
        {
            var child = VisualTreeHelper.GetChild(parent, index);
            if (child is T match) yield return match;
            foreach (var nested in Descendants<T>(child)) yield return nested;
        }
    }

    private static void Click(ButtonBase button) => button.RaiseEvent(new RoutedEventArgs(ButtonBase.ClickEvent));

    /// <summary>What a mouse click does to a check box or option button: its new state shows at once, then Click.</summary>
    private static void Toggle(ToggleButton button)
    {
        button.SetCurrentValue(ToggleButton.IsCheckedProperty, button is RadioButton || button.IsChecked != true);
        Click(button);
    }

    private static string Text(TextBlock block) => string.Concat(Runs(block.Inlines).Select(run => run.Text));

    private static IEnumerable<Run> Runs(InlineCollection inlines) => inlines.SelectMany(inline => inline switch
    {
        Run run => [run],
        Span span => Runs(span.Inlines),
        _ => Enumerable.Empty<Run>(),
    });

    [Fact]
    public void TheTabIsFetchedWhenShownAndOpensOnTonight() => ui.Run(() =>
    {
        var bridge = new FakeBridge(Sample.Events()) { Kitchen = KitchenSample.Snapshot() };
        var window = UiFixture.Open(Sample.Store(), () => KitchenSample.Now, bridge);
        try
        {
            Assert.DoesNotContain("kitchen", bridge.Calls);
            window.ShowSection("kitchen");
            UiFixture.Settle();
            Assert.Equal(["kitchen"], bridge.Calls);
            Assert.True(window.KitchenTab.IsChecked);
            Assert.Equal(Visibility.Visible, window.KitchenPanel.Visibility);
            Assert.Equal(Visibility.Collapsed, window.ListArea.Visibility);
            Assert.False(window.FindBox.IsEnabled);
            Assert.False(window.CalendarModeButton.IsEnabled);
            Assert.Equal("5 on the list", window.CountText.Text);
            var panel = window.KitchenPanel;
            Assert.Equal(Visibility.Visible, panel.WeekView.Visibility);
            Assert.Equal("Shopping list (5)", panel.ListPartText.Text);
            Assert.Equal("Updated 5:30 PM", panel.UpdatedText.Text);

            // Tonight first, then the rest of the week in order, its box, and next week.
            Assert.Equal(
            [
                "Tonight", "Thu Turkey chili",
                "This week", "Tue Oven-baked salmon", "Wed Chicken fajita bowls", "Fri Leftover turkey chili",
                "Sat Sheet-pan lemon chicken", "Sun Beef and broccoli stir-fry", "Mon Leftovers night",
                "Breakfast & lunch", "(box)",
                "Next week", "Tue Turkey meatballs", "Wed Black bean tacos",
            ], DayRows(window));
            Assert.Equal(Thursday, panel.SelectedDate);
            Assert.Equal("sweet potato · Cooked · Loved it", panel.DayList.Items.OfType<MealDayRow>().Single(row => row.Day.Date == Thursday.AddDays(-2)).Secondary);

            // Tonight's recipe: the day's note with its bold part, ingredients and numbered steps.
            Assert.Equal("Tonight · Thursday, October 29", panel.DayWhen.Text);
            Assert.Equal("Turkey chili", Text(panel.DayDinner));
            Assert.Equal("With cornbread muffins", Text(panel.DayBase));
            Assert.Equal("Start it by 5:30 so it simmers an hour", Text(panel.DayNoteText));
            Assert.Equal("5:30", Assert.Single(panel.DayNoteText.Inlines.OfType<Bold>()).Inlines.OfType<Run>().Single().Text);
            Assert.Equal(Visibility.Visible, panel.RecipePanel.Visibility);
            // The recipe has the dinner's name; it is not said twice.
            Assert.Equal(Visibility.Collapsed, panel.RecipeTitle.Visibility);
            Assert.Equal(["1.", "2.", "3.", "4.", "5."], panel.StepsList.Items.Cast<StepRow>().Select(step => step.Number));
            Assert.Equal("Note: Half the beans can be mashed for a thicker chili.", Text(panel.RecipeNote));
            Assert.Equal("Meal Plan · Oct 27–Nov 2, 2026", Text(panel.PlanTitle));
            Assert.Equal("For 2 · quick weeknights · make-ahead lunches · Ordered · delivery Mon 4–6pm · no substitutions", Text(panel.PlanTagline));
            Assert.False(panel.CookedYes.IsChecked == true || panel.RatingLoved.IsChecked == true);

            // A leftovers night shows no recipe; next week's draft says so.
            panel.SelectDay(Thursday.AddDays(1));
            Assert.Equal(Visibility.Collapsed, panel.RecipePanel.Visibility);
            Assert.Equal("Friday, October 30 · Leftovers", panel.DayWhen.Text);
            panel.SelectDay(new DateOnly(2026, 11, 3));
            Assert.Equal(Visibility.Visible, panel.PlanDraft.Visibility);

            // Leaving the tab gives the reminders their list and Find back.
            window.ShowSection("life");
            UiFixture.Settle();
            Assert.Equal(Visibility.Visible, window.ListArea.Visibility);
            Assert.Equal(Visibility.Collapsed, window.KitchenPanel.Visibility);
            Assert.True(window.FindBox.IsEnabled);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void AddingAnItemPutsItOnTheListOnce() => ui.Run(() =>
    {
        var (window, bridge) = OpenKitchen(part: KitchenView.ListPartName);
        var panel = window.KitchenPanel;
        try
        {
            Assert.Equal(Visibility.Visible, panel.ShoppingView.Visibility);
            Assert.Equal(["To buy (3)", "Aluminum foil", "Olive oil", "Paper towels", "On order (2)", "Salmon fillets", "Sweet potatoes", "Got it (1)", "Milk"],
                ListRows(window));

            panel.AddBox.Text = "  Dish soap ";
            Click(panel.AddButton);
            UiFixture.Settle();
            Assert.Equal(["kitchen", "add Dish soap", "kitchen"], bridge.Calls);
            Assert.Equal("", panel.AddBox.Text);
            Assert.Equal(["To buy (4)", "Aluminum foil", "Olive oil", "Paper towels", "Dish soap"], ListRows(window).Take(5));
            Assert.Equal("Added to the shopping list: Dish soap", window.ActionBarText.Text);
            Assert.Equal("6 on the list", window.CountText.Text);
            Assert.Equal("Shopping list (6)", panel.ListPartText.Text);

            // The same thing again, spelled differently: the server returns the open one (rule 1).
            Assert.True(panel.AddItemAsync("aluminum FOIL").IsCompletedSuccessfully);
            UiFixture.Settle();
            Assert.Equal("Already on the shopping list: Aluminum foil", window.ActionBarText.Text);
            Assert.Single(ListRows(window), name => name == "Aluminum foil");

            // An empty box sends nothing.
            bridge.Calls.Clear();
            panel.AddBox.Text = "   ";
            Click(panel.AddButton);
            Assert.Empty(bridge.Calls);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void TickingAnItemMovesItToGotItAndUntickingPutsItBack() => ui.Run(() =>
    {
        var (window, bridge) = OpenKitchen(part: KitchenView.ListPartName);
        var panel = window.KitchenPanel;
        try
        {
            Toggle(Control<CheckBox>(panel.ShoppingItems, row => row is ShoppingRow { Name: "Olive oil" }));
            UiFixture.Settle();
            Assert.Equal(["kitchen", "item koil00000002 got", "kitchen"], bridge.Calls);
            Assert.Equal(["To buy (2)", "Aluminum foil", "Paper towels", "On order (2)", "Salmon fillets", "Sweet potatoes", "Got it (2)", "Olive oil", "Milk"],
                ListRows(window));
            var oil = panel.ShoppingItems.Items.OfType<ShoppingRow>().Single(row => row.Name == "Olive oil");
            Assert.True(oil.IsGot);
            Assert.Equal("1 bottle · Got today", oil.Detail);
            Assert.True(Control<CheckBox>(panel.ShoppingItems, row => row == oil).IsChecked);
            // The fetch after the edit brings the server's own change: the pantry has olive oil again (rule 3).
            Assert.Equal(PantryStatus.Have, panel.Snapshot!.Pantry.Single(item => item.Name == "Olive oil").Status);

            // Unticking it in "Got it" puts it back on the list.
            Toggle(Control<CheckBox>(panel.ShoppingItems, row => row is ShoppingRow { Name: "Olive oil" }));
            UiFixture.Settle();
            Assert.Equal("item koil00000002 need", bridge.Calls[^2]);
            Assert.Equal(["To buy (3)", "Aluminum foil", "Olive oil", "Paper towels"], ListRows(window).Take(4));

            // An item on an order is ticked off the same way when it arrives.
            Toggle(Control<CheckBox>(panel.ShoppingItems, row => row is ShoppingRow { Name: "Salmon fillets" }));
            UiFixture.Settle();
            Assert.Equal(["On order (1)", "Sweet potatoes", "Got it (2)", "Salmon fillets", "Milk"], ListRows(window)[^5..]);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void RemovingAnItemCanBeUndone() => ui.Run(() =>
    {
        var (window, bridge) = OpenKitchen(part: KitchenView.ListPartName);
        var panel = window.KitchenPanel;
        try
        {
            Click(Control<Button>(panel.ShoppingItems, row => row is ShoppingRow { Name: "Paper towels" }));
            UiFixture.Settle();
            Assert.Equal("remove ktowel000003", bridge.Calls[^2]);
            Assert.DoesNotContain("Paper towels", ListRows(window));
            Assert.Equal("Removed from the shopping list: Paper towels", window.ActionBarText.Text);

            Click(window.ActionBarLink);
            UiFixture.Settle();
            Assert.Equal("add Paper towels as need", bridge.Calls[^2]);
            var back = Row(panel, "Paper towels");
            Assert.Equal("the big pack", back.Item.Note);

            // A line on an order comes back on the order, with it: never to buy again (a second order).
            Click(Control<Button>(panel.ShoppingItems, row => row is ShoppingRow { Name: "Salmon fillets" }));
            UiFixture.Settle();
            Click(window.ActionBarLink);
            UiFixture.Settle();
            Assert.Equal("add Salmon fillets as ordered [Walmart #200000000001, Mon 11/2]", bridge.Calls[^2]);
            var salmon = Row(panel, "Salmon fillets").Item;
            Assert.Equal((ShoppingStatus.Ordered, "Walmart #200000000001, Mon 11/2", "1 lb"), (salmon.Status, salmon.OrderRef, salmon.Qty));
            Assert.Equal(["On order (2)", "Sweet potatoes", "Salmon fillets"], ListRows(window)[4..7]);

            // A line bought this week comes back under Got it.
            Click(Control<Button>(panel.ShoppingItems, row => row is ShoppingRow { Name: "Milk" }));
            UiFixture.Settle();
            Click(window.ActionBarLink);
            UiFixture.Settle();
            Assert.Equal("add Milk as got", bridge.Calls[^2]);
            Assert.Equal(["Got it (1)", "Milk"], ListRows(window)[^2..]);
            Assert.True(Row(panel, "Milk").IsGot);

            // Bridge 0.10.0 does not know restore and puts it back to buy; what it answers is shown.
            bridge.OldBridge = true;
            Click(Control<Button>(panel.ShoppingItems, row => row is ShoppingRow { Name: "Sweet potatoes" }));
            UiFixture.Settle();
            Click(window.ActionBarLink);
            UiFixture.Settle();
            Assert.Equal("add Sweet potatoes as ordered [Walmart #200000000001, Mon 11/2]", bridge.Calls[^2]);
            Assert.Equal(ShoppingStatus.Need, Row(panel, "Sweet potatoes").Item.Status);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void ADinnerCanBeMarkedRatedAndNoted() => ui.Run(() =>
    {
        var (window, bridge) = OpenKitchen();
        var panel = window.KitchenPanel;
        try
        {
            Toggle(panel.CookedYes);
            Toggle(panel.RatingLoved);
            UiFixture.Settle();
            Assert.Equal(["kitchen", "note 2026-10-29 cooked=yes", "kitchen", "note 2026-10-29 rating=loved", "kitchen"], bridge.Calls);
            Assert.True(panel.CookedYes.IsChecked);
            Assert.True(panel.RatingLoved.IsChecked);
            Assert.False(panel.RatingOk.IsChecked);
            Assert.Equal("cornbread muffins · Cooked · Loved it", panel.DayList.Items.OfType<MealDayRow>().First().Secondary);

            // Another rating replaces it; choosing the chosen one again clears it.
            Toggle(panel.RatingNo);
            UiFixture.Settle();
            Assert.Equal("note 2026-10-29 rating=no", bridge.Calls[^2]);
            Assert.True(panel.RatingNo.IsChecked);
            Assert.False(panel.RatingLoved.IsChecked);
            Toggle(panel.RatingNo);
            UiFixture.Settle();
            Assert.Equal("note 2026-10-29 rating=", bridge.Calls[^2]);
            Assert.False(panel.RatingNo.IsChecked);

            // The note is saved with its button, which stays off until there is something new to save.
            Assert.False(panel.SaveNoteButton.IsEnabled);
            panel.DinnerNoteBox.Text = "Double the cumin ";
            Assert.True(panel.SaveNoteButton.IsEnabled);
            Click(panel.SaveNoteButton);
            UiFixture.Settle();
            Assert.Equal("note 2026-10-29 note=Double the cumin", bridge.Calls[^2]);
            Assert.Equal("Double the cumin", panel.DinnerNoteBox.Text);
            Assert.False(panel.SaveNoteButton.IsEnabled);

            // Another day shows its own note.
            panel.SelectDay(Thursday.AddDays(-2));
            Assert.Equal("Less dill next time", panel.DinnerNoteBox.Text);
            Assert.True(panel.RatingLoved.IsChecked);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void AFailedEditChangesNothingOnScreenAndSaysWhy() => ui.Run(() =>
    {
        var (window, bridge) = OpenKitchen(part: KitchenView.ListPartName);
        var panel = window.KitchenPanel;
        try
        {
            bridge.Failure = FakeBridge.Offline();
            var before = ListRows(window);
            Toggle(Control<CheckBox>(panel.ShoppingItems, row => row is ShoppingRow { Name: "Aluminum foil" }));
            UiFixture.Settle();
            Assert.Equal("item kfoil0000001 got", bridge.Calls[^1]);
            Assert.Equal("Could not tick it off: No such host is known.", panel.LastProblem);
            Assert.Equal("Could not tick it off.", window.SyncStatus.Text);
            Assert.Equal(before, ListRows(window));
            Assert.False(Control<CheckBox>(panel.ShoppingItems, row => row is ShoppingRow { Name: "Aluminum foil" }).IsChecked);

            panel.AddBox.Text = "Dish soap";
            Click(panel.AddButton);
            Assert.Equal("Could not add it to the shopping list: No such host is known.", panel.LastProblem);
            // What was typed stays, to try again.
            Assert.Equal("Dish soap", panel.AddBox.Text);
            Assert.Equal(before, ListRows(window));

            panel.ShowPart(KitchenView.PantryPartName);
            Assert.True(panel.CorrectAsync("pturkey00004", CorrectionKind.Out).IsCompletedSuccessfully);
            Assert.Equal("Could not tell Claude it's out: No such host is known.", panel.LastProblem);
            Assert.Equal("Running low", OnHandRows(panel).Single(row => row.Name == "Ground turkey").Chip);
            Assert.Equal(Visibility.Collapsed, window.ActionBar.Visibility);
            panel.TellBox.Text = "Tossed the parsley";
            Click(panel.SendButton);
            Assert.Equal("Could not send it to Claude: No such host is known.", panel.LastProblem);
            Assert.Equal("Tossed the parsley", panel.TellBox.Text);

            panel.ShowPart(KitchenView.WeekPartName);
            Toggle(panel.RatingOk);
            UiFixture.Settle();
            Assert.Equal("Could not save how dinner went: No such host is known.", panel.LastProblem);
            Assert.False(panel.RatingOk.IsChecked);

            // Each of those said why in a message box.
            Assert.Equal(5, window.KitchenProblemsReported);

            // A thing someone else removed meanwhile: no message box; the kitchen is fetched again and it is gone.
            bridge.Failure = null;
            bridge.Kitchen.List.RemoveAll(item => item.Name == "Paper towels");
            bridge.Kitchen.Revision++;
            panel.ShowPart(KitchenView.ListPartName);
            Toggle(Control<CheckBox>(panel.ShoppingItems, row => row is ShoppingRow { Name: "Paper towels" }));
            UiFixture.Settle();
            Assert.Equal(["item ktowel000003 got", "kitchen"], bridge.Calls[^2..]);
            Assert.Equal("Could not tick it off: Not found on the calendar service: Item not found", panel.LastProblem);
            Assert.Equal("Could not tick it off: it was changed or removed elsewhere.", window.SyncStatus.Text);
            Assert.Equal(5, window.KitchenProblemsReported);
            Assert.DoesNotContain("Paper towels", ListRows(window));

            // The same for a pantry item Claude removed.
            bridge.Kitchen.Pantry.RemoveAll(item => item.Name == "Salsa");
            bridge.Kitchen.Revision++;
            panel.ShowPart(KitchenView.PantryPartName);
            Assert.True(panel.CorrectAsync("psalsa000007", CorrectionKind.Out).IsCompletedSuccessfully);
            Assert.Equal(["correction out psalsa000007", "kitchen"], bridge.Calls[^2..]);
            Assert.Equal(5, window.KitchenProblemsReported);
            Assert.DoesNotContain(OnHandRows(panel), row => row.Name == "Salsa");
        }
        finally { window.Close(); }
    });

    [Fact]
    public void UnpairedTheTabSaysItNeedsAConnection() => ui.Run(() =>
    {
        var (window, bridge) = OpenKitchen(Sample.Store(events: [], paired: false));
        var panel = window.KitchenPanel;
        try
        {
            Assert.DoesNotContain("kitchen", bridge.Calls);
            Assert.Equal(Visibility.Visible, panel.MessagePanel.Visibility);
            Assert.Equal("Can't reach the cloud calendar. Kitchen needs a connection.", panel.MessageText.Text);
            Assert.Equal(Visibility.Collapsed, panel.WeekView.Visibility);
            Assert.Equal(Visibility.Collapsed, panel.RetryLink.Visibility);
            Assert.False(panel.RefreshButton.IsEnabled);
            Assert.Equal("", window.CountText.Text);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void OfflineTheTabSaysSoAndRecovers() => ui.Run(() =>
    {
        var bridge = new FakeBridge(Sample.Events()) { Kitchen = KitchenSample.Snapshot(), Failure = FakeBridge.Offline() };
        var now = KitchenSample.Now;
        var window = UiFixture.Open(Sample.Store(), () => now, bridge);
        var panel = window.KitchenPanel;
        try
        {
            window.ShowSection("kitchen");
            UiFixture.Settle();
            Assert.Equal(Visibility.Visible, panel.MessagePanel.Visibility);
            Assert.Equal(KitchenView.OfflineMessage, panel.MessageText.Text);
            Assert.Equal("No such host is known.", panel.MessageDetail.Text);
            Assert.Equal(Visibility.Visible, panel.RetryLink.Visibility);

            bridge.Failure = null;
            Click(panel.RetryLink);
            UiFixture.Settle();
            Assert.Equal(Visibility.Collapsed, panel.MessagePanel.Visibility);
            Assert.Equal(Visibility.Visible, panel.WeekView.Visibility);

            // Once loaded, a failed refresh keeps what is shown and says so above it.
            bridge.Failure = FakeBridge.Offline();
            now += MainWindow.KitchenIdleRefresh;
            Assert.True(window.KitchenTickAsync().IsCompletedSuccessfully);
            Assert.Equal(Visibility.Visible, panel.OfflineBar.Visibility);
            Assert.Equal("Can't reach the cloud calendar. Kitchen needs a connection. Showing what it said at 5:30 PM.", panel.OfflineText.Text);
            Assert.Equal(Visibility.Visible, panel.WeekView.Visibility);
            Assert.Equal("Turkey chili", Text(panel.DayDinner));

            // It keeps trying at the same pace, and recovers.
            bridge.Failure = null;
            now += MainWindow.KitchenIdleRefresh;
            Assert.True(window.KitchenTickAsync().IsCompletedSuccessfully);
            Assert.Equal(Visibility.Collapsed, panel.OfflineBar.Visibility);

            // A service without the kitchen yet (an older bridge) says so in plain words.
            bridge.Failure = new BridgeException(System.Net.HttpStatusCode.NotFound, "<!DOCTYPE html><pre>Cannot GET /v1/kitchen</pre>");
            Assert.True(panel.LoadAsync().IsCompletedSuccessfully);
            Assert.Equal("This calendar service has no Kitchen yet.", panel.LoadProblem);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void TheTabIsFetchedEvery20SecondsWhenActiveAnd60OtherwiseAndOnlyWhileItShows() => ui.Run(() =>
    {
        var bridge = new FakeBridge(Sample.Events()) { Kitchen = KitchenSample.Snapshot() };
        var now = KitchenSample.Now;
        var active = true;
        var window = UiFixture.Open(Sample.Store(), () => now, bridge);
        // The test's window never takes the focus; it says whether it would have it.
        window.IsWindowActive = () => active;
        int Fetches() => bridge.Calls.Count(call => call == "kitchen");
        void After(double seconds)
        {
            now += TimeSpan.FromSeconds(seconds);
            Assert.True(window.KitchenTickAsync().IsCompletedSuccessfully);
        }
        try
        {
            // Not on the Kitchen tab: no timer, no fetch, however long it is.
            Assert.Null(window.KitchenCadence);
            Assert.Null(window.KitchenRefreshWait);
            After(600);
            Assert.True(window.RefreshKitchenIfShownAsync().IsCompletedSuccessfully);
            Assert.Equal(0, Fetches());

            // Shown: fetched at once, then every 20 seconds while the window is active.
            window.ShowSection("kitchen");
            Assert.Equal(1, Fetches());
            Assert.Equal(MainWindow.KitchenActiveRefresh, window.KitchenCadence);
            Assert.Equal(TimeSpan.FromSeconds(20), window.KitchenRefreshWait);
            After(19);
            Assert.Equal(1, Fetches());
            Assert.Equal(TimeSpan.FromSeconds(1), window.KitchenRefreshWait);
            After(1);
            Assert.Equal(2, Fetches());

            // In the background: every 60 seconds.
            active = false;
            Assert.Equal(MainWindow.KitchenIdleRefresh, window.KitchenCadence);
            Assert.Equal(TimeSpan.FromSeconds(60), window.KitchenRefreshWait);
            After(30);
            After(29);
            Assert.Equal(2, Fetches());
            After(1);
            Assert.Equal(3, Fetches());

            // Activated: once, at once; the 20-second pace counts from there.
            now += TimeSpan.FromSeconds(10);
            active = true;
            Assert.True(window.RefreshKitchenIfShownAsync().IsCompletedSuccessfully);
            Assert.Equal(4, Fetches());
            Assert.Equal(TimeSpan.FromSeconds(20), window.KitchenRefreshWait);
            // A show or restore that comes with the activation does not fetch again.
            Assert.True(window.RefreshKitchenIfShownAsync().IsCompletedSuccessfully);
            Assert.Equal(4, Fetches());

            // Any fetch resets the count: the next timed one is 20 seconds after an edit's own fetch.
            After(15);
            Assert.True(window.KitchenPanel.AddItemAsync("Dish soap").IsCompletedSuccessfully);
            Assert.Equal(5, Fetches());
            After(15);
            Assert.Equal(5, Fetches());
            After(5);
            Assert.Equal(6, Fetches());

            // Another tab: fetching stops.
            window.ShowSection("all");
            Assert.Null(window.KitchenCadence);
            After(600);
            Assert.True(window.RefreshKitchenIfShownAsync().IsCompletedSuccessfully);
            Assert.Equal(6, Fetches());

            // The reminders' minute sync never fetches the kitchen.
            window.ShowSection("kitchen");
            bridge.Calls.Clear();
            Assert.True(window.SyncAsync(showActivity: false).IsCompletedSuccessfully);
            Assert.Equal(["sync"], bridge.Calls);
        }
        finally { window.Close(); }
    });

    private static EstimateRow[] OnHandRows(KitchenView panel) => panel.OnHandItems.Items.OfType<EstimateRow>().ToArray();

    private static string[] OnHandList(KitchenView panel) => panel.OnHandItems.Items.Cast<object>().Select(row => row switch
    {
        KitchenHeaderRow caption => $"[{caption.Label}]",
        CorrectionRow correction => $"{correction.Said} | {correction.Answer}",
        ShowAllRow more => $"<{more.Label}>",
        KitchenNoteRow note => note.Text,
        EstimateRow item => $"{item.Name} = {item.Chip}",
        _ => "?",
    }).ToArray();

    [Fact]
    public void OnHandShowsClaudesEstimateAndTheCorrections() => ui.Run(() =>
    {
        var (window, _) = OpenKitchen(part: KitchenView.PantryPartName);
        var panel = window.KitchenPanel;
        try
        {
            Assert.Equal(Visibility.Visible, panel.PantryView.Visibility);
            Assert.Equal("On hand", panel.PantryPartText.Text);
            Assert.Equal("What Claude thinks you have. Updated Mon 10/26.", panel.OnHandHeader.Text);
            Assert.Equal(
            [
                "[Your corrections]",
                "Black beans: 2 cans left | Waiting for Claude",
                "Tossed the cilantro | Waiting for Claude",
                "We're out: Olive oil | Applied: Marked out and added to the shopping list.",
                "<Show all 5>",
                "[Canned & jarred]", "Diced tomatoes = Running low", "Black beans = Have",
                "[Freezer]", "Ground turkey = Running low", "Frozen broccoli = Have",
                "[Fridge & counter]", "Olive oil = Probably out", "Eggs = Have",
                "[Sauces & condiments]", "Salsa = Running low", "Low-sodium soy sauce = Have",
                "[Spices]", "Cumin = Have",
            ], OnHandList(panel));
            Assert.Equal("(2 waiting for Claude)", panel.OnHandItems.Items.OfType<KitchenHeaderRow>().First().Detail);
            Assert.Equal("1 lb left after Thu chili", OnHandRows(panel).Single(row => row.Name == "Ground turkey").Basis);
            Assert.False(OnHandRows(panel).Single(row => row.Name == "Cumin").HasBasis);

            // Show all, and back.
            Click(Control<Button>(panel.OnHandItems, row => row is ShowAllRow));
            Assert.Equal(5, panel.OnHandItems.Items.OfType<CorrectionRow>().Count());
            Assert.Equal("<Show the newest 3>", OnHandList(panel)[6]);
            Click(Control<Button>(panel.OnHandItems, row => row is ShowAllRow));
            Assert.Equal(3, panel.OnHandItems.Items.OfType<CorrectionRow>().Count());

            // Running low and out only.
            Toggle(panel.LowOnlyBox);
            Assert.Equal(["Diced tomatoes", "Ground turkey", "Olive oil", "Salsa"], OnHandRows(panel).Select(row => row.Name));
            Toggle(panel.LowOnlyBox);
            Assert.Equal(9, OnHandRows(panel).Length);

            // No radio buttons, add form or remove buttons: only the selected row has buttons, and only these.
            Assert.Empty(Descendants<RadioButton>(panel.PantryView));
            Assert.Null(panel.SelectedPantryId);
            var salsa = Control<FrameworkElement>(panel.OnHandItems, row => row is EstimateRow { Name: "Salsa" }, element => element.Name == "Row");
            salsa.RaiseEvent(new System.Windows.Input.MouseButtonEventArgs(System.Windows.Input.Mouse.PrimaryDevice, 0, System.Windows.Input.MouseButton.Left)
            {
                RoutedEvent = UIElement.MouseLeftButtonUpEvent,
            });
            UiFixture.Settle();
            Assert.Equal("psalsa000007", panel.SelectedPantryId);
            var buttons = Descendants<Button>(panel.OnHandItems).Where(button => button.IsVisible).Select(button => button.Content as string ?? "").ToArray();
            Assert.Equal(["Withdraw", "Withdraw", "Show all 5", "We're out", "We have it", "Something else…"], buttons);
            // Clicked again, it closes.
            salsa.RaiseEvent(new System.Windows.Input.MouseButtonEventArgs(System.Windows.Input.Mouse.PrimaryDevice, 0, System.Windows.Input.MouseButton.Left)
            {
                RoutedEvent = UIElement.MouseLeftButtonUpEvent,
            });
            Assert.Null(panel.SelectedPantryId);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void WereOutTellsClaudeAndPutsItOnTheList() => ui.Run(() =>
    {
        var (window, bridge) = OpenKitchen(part: KitchenView.PantryPartName);
        var panel = window.KitchenPanel;
        try
        {
            Assert.Equal("5 on the list", window.CountText.Text);
            panel.SelectPantryItem("ptomato00002");
            Click(Control<Button>(panel.OnHandItems, row => row is EstimateRow { Name: "Diced tomatoes" }, button => (string)button.Content == "We're out"));
            UiFixture.Settle();
            Assert.Equal(["kitchen", "correction out ptomato00002", "kitchen"], bridge.Calls);
            // The reply, and the list one longer.
            Assert.Equal("Diced tomatoes: Marked out and added to the shopping list.", window.ActionBarText.Text);
            Assert.Equal("6 on the list", window.CountText.Text);
            Assert.Equal("Shopping list (6)", panel.ListPartText.Text);
            var tomatoes = OnHandRows(panel).Single(row => row.Name == "Diced tomatoes");
            Assert.Equal(("Probably out", $"{FakeBridge.DeviceName} said it's out"), (tomatoes.Chip, tomatoes.Basis));
            // Still open after the refresh, for the next correction.
            Assert.True(tomatoes.IsSelected);
            Assert.Equal("We're out: Diced tomatoes | Applied: Marked out and added to the shopping list.",
                OnHandList(panel).Single(row => row.StartsWith("We're out: Diced", StringComparison.Ordinal)));
            Assert.Equal("Out in the pantry", panel.Snapshot!.List.Where(item => item.Name == "Diced tomatoes")
                .Select(item => KitchenText.ItemDetail(item, KitchenSample.Today)).Single());

            // Out of something already on the list: the reply says so and nothing is added.
            Assert.True(panel.CorrectAsync("poil00000006", CorrectionKind.Out).IsCompletedSuccessfully);
            Assert.Equal("Olive oil: Marked out; it was already on the shopping list.", window.ActionBarText.Text);
            Assert.Equal("6 on the list", window.CountText.Text);

            // We have it.
            panel.SelectPantryItem("pturkey00004");
            Click(Control<Button>(panel.OnHandItems, row => row is EstimateRow { Name: "Ground turkey" }, button => (string)button.Content == "We have it"));
            UiFixture.Settle();
            Assert.Equal("correction have pturkey00004", bridge.Calls[^2]);
            Assert.Equal("Ground turkey: Marked as on hand.", window.ActionBarText.Text);
            Assert.Equal("Have", OnHandRows(panel).Single(row => row.Name == "Ground turkey").Chip);
            Assert.Equal("6 on the list", window.CountText.Text);

            // We have the olive oil the pantry put on the list: it comes off the list (bridge 0.11.0),
            // at once, before the fetch afterwards answers.
            bridge.HoldFetches = true;
            Assert.False(panel.CorrectAsync("poil00000006", CorrectionKind.Have).IsCompleted);
            Assert.Equal("Olive oil: Marked as on hand and taken off the shopping list.", window.ActionBarText.Text);
            Assert.Equal("5 on the list", window.CountText.Text);
            Assert.DoesNotContain(panel.Snapshot!.List, item => item.Id == "koil00000002");
            bridge.ReleaseFetch();
            UiFixture.Settle();
            Assert.Equal("5 on the list", window.CountText.Text);

            // Each correction went with an id of its own, so a second send of it is not stored twice.
            Assert.Equal(4, bridge.CorrectionClientIds.Count);
            Assert.All(bridge.CorrectionClientIds, id => Assert.Matches("^[0-9a-f]{32}$", id));
            Assert.Equal(4, bridge.CorrectionClientIds.Distinct().Count());
        }
        finally { window.Close(); }
    });

    [Fact]
    public void ANoteToClaudeWaitsOpenAndCanBeWithdrawn() => ui.Run(() =>
    {
        var (window, bridge) = OpenKitchen(part: KitchenView.PantryPartName);
        var panel = window.KitchenPanel;
        try
        {
            // A plain note.
            panel.TellBox.Text = " Out of eggs, have 2 cans of beans ";
            Click(panel.SendButton);
            UiFixture.Settle();
            Assert.Equal(["kitchen", "correction note \"Out of eggs, have 2 cans of beans\"", "kitchen"], bridge.Calls);
            Assert.Equal("", panel.TellBox.Text);
            var sent = panel.Snapshot!.Corrections.Single(item => item.Text == "Out of eggs, have 2 cans of beans");
            Assert.Equal((CorrectionStatus.Open, (string?)null), (sent.Status, sent.ItemId));
            Assert.Equal("Out of eggs, have 2 cans of beans | Waiting for Claude", OnHandList(panel)[3]);
            Assert.Equal("(3 waiting for Claude)", panel.OnHandItems.Items.OfType<KitchenHeaderRow>().First().Detail);

            // Something else... on a row: the box starts with its name, and the note is about it.
            panel.SelectPantryItem("pbroc0000003");
            Click(Control<Button>(panel.OnHandItems, row => row is EstimateRow { Name: "Frozen broccoli" }, button => (string)button.Content == "Something else…"));
            Assert.Equal("Frozen broccoli: ", panel.TellBox.Text);
            Assert.Equal("pbroc0000003", panel.TellItemId);
            Assert.Equal(Visibility.Visible, panel.AboutItem.Visibility);
            Assert.Equal("About Frozen broccoli", panel.AboutItemText.Text);
            panel.TellBox.Text += "half a bag left";
            Click(panel.SendButton);
            UiFixture.Settle();
            Assert.Equal("correction note pbroc0000003 \"Frozen broccoli: half a bag left\"", bridge.Calls[^2]);
            Assert.Null(panel.TellItemId);
            Assert.Equal(Visibility.Collapsed, panel.AboutItem.Visibility);

            // Emptied, the box is no longer about the item.
            panel.StartNoteAbout("pcumin000009");
            panel.TellBox.Text = "";
            Assert.Null(panel.TellItemId);

            // Withdraw an open note; Undo sends it again.
            Click(Control<Button>(panel.OnHandItems, row => row is CorrectionRow { Correction.Id: "cnote0000002" }, button => (string)button.Content == "Withdraw"));
            UiFixture.Settle();
            Assert.Equal("withdraw cnote0000002", bridge.Calls[^2]);
            Assert.DoesNotContain(panel.Snapshot!.Corrections, item => item.Id == "cnote0000002");
            Assert.Equal("Withdrawn: Tossed the cilantro", window.ActionBarText.Text);
            Click(window.ActionBarLink);
            UiFixture.Settle();
            Assert.Equal("correction note \"Tossed the cilantro\"", bridge.Calls[^2]);
            Assert.Single(panel.Snapshot!.Corrections, item => item.Text == "Tossed the cilantro" && item.IsOpen);

            // Handled ones cannot be withdrawn.
            Click(Control<Button>(panel.OnHandItems, row => row is ShowAllRow));
            Assert.DoesNotContain(Descendants<Button>(panel.OnHandItems),
                button => button.IsVisible && (string?)button.Content == "Withdraw" && button.DataContext is CorrectionRow { IsOpen: false });
        }
        finally { window.Close(); }
    });

    [Fact]
    public void AnEmptyPantrySaysWhenItFillsIn() => ui.Run(() =>
    {
        var bridge = new FakeBridge(Sample.Events()) { Kitchen = new KitchenSnapshot() };
        var window = UiFixture.Open(Sample.Store(), () => KitchenSample.Now, bridge);
        try
        {
            window.ShowSection("kitchen");
            window.KitchenPanel.ShowPart(KitchenView.PantryPartName);
            Assert.Equal([KitchenView.EmptyPantryMessage], OnHandList(window.KitchenPanel));
            Assert.Equal("Claude fills this in after the next grocery order.", KitchenView.EmptyPantryMessage);
            Assert.Equal("What Claude thinks you have.", window.KitchenPanel.OnHandHeader.Text);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void ARefreshWithTheSameRevisionLeavesTheViewAlone() => ui.Run(() =>
    {
        var (window, bridge) = OpenKitchen();
        var panel = window.KitchenPanel;
        try
        {
            panel.SelectDay(Thursday.AddDays(2));
            panel.DinnerNoteBox.Text = "Half a lemon is enough";
            var days = panel.DayList.ItemsSource;
            var list = panel.ShoppingItems.ItemsSource;

            Assert.True(panel.LoadAsync().IsCompletedSuccessfully);
            Assert.Same(days, panel.DayList.ItemsSource);
            Assert.Same(list, panel.ShoppingItems.ItemsSource);

            // Someone adds to the list on a phone: the next refresh draws it, and keeps the open day and the note being typed.
            bridge.Kitchen.List.Add(new ShoppingItem { Id = "kphone000009", Name = "Lemons", Status = ShoppingStatus.Need, AddedAt = KitchenSample.Now });
            bridge.Kitchen.Revision++;
            Assert.True(panel.LoadAsync().IsCompletedSuccessfully);
            Assert.NotSame(list, panel.ShoppingItems.ItemsSource);
            Assert.Contains("Lemons", ListRows(window));
            Assert.Equal(Thursday.AddDays(2), panel.SelectedDate);
            Assert.Equal("Half a lemon is enough", panel.DinnerNoteBox.Text);
            Assert.True(panel.SaveNoteButton.IsEnabled);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void WithoutAPublishedWeekTheTabSaysWhenOneComes() => ui.Run(() =>
    {
        var (window, bridge) = OpenKitchen();
        var panel = window.KitchenPanel;
        try
        {
            // Only next week: this week's plan is missing, next week is listed.
            bridge.Kitchen.Week = null;
            bridge.Kitchen.Revision++;
            Assert.True(panel.LoadAsync().IsCompletedSuccessfully);
            Assert.Equal(Visibility.Visible, panel.NoWeekBar.Visibility);
            Assert.Equal(["Next week", "Tue Turkey meatballs", "Wed Black bean tacos"], DayRows(window));
            Assert.Equal(new DateOnly(2026, 11, 3), panel.SelectedDate);

            bridge.Kitchen.NextWeek = null;
            bridge.Kitchen.Revision++;
            Assert.True(panel.LoadAsync().IsCompletedSuccessfully);
            Assert.Equal(Visibility.Visible, panel.NoPlanPanel.Visibility);
            Assert.Equal(Visibility.Collapsed, panel.WeekPanes.Visibility);
            Assert.Equal(Visibility.Collapsed, panel.PlanHeader.Visibility);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void TheTabAndItsPartAreRemembered() => ui.Run(() =>
    {
        var store = Sample.Store();
        var (window, _) = OpenKitchen(store);
        try
        {
            Click(window.KitchenPanel.PantryPart);
            Assert.Equal(("kitchen", "pantry"), (store.LoadView().Section, store.LoadView().KitchenPart));
        }
        finally { window.Close(); }

        var bridge = new FakeBridge { Kitchen = KitchenSample.Snapshot() };
        window = UiFixture.Open(store, () => KitchenSample.Now, bridge);
        try
        {
            Assert.True(window.KitchenTab.IsChecked);
            Assert.Equal(["kitchen"], bridge.Calls);
            Assert.Equal(Visibility.Visible, window.KitchenPanel.PantryView.Visibility);
        }
        finally { window.Close(); }
    });

    // ------------------------------------------------------------ the day moving on

    [Fact]
    public void ATabLeftOpenOvernightMovesOnToTheNewDay() => ui.Run(() =>
    {
        // Thursday, 11:50 PM: the olive oil was bought this evening.
        var now = Sample.Local(2026, 10, 29, 23, 50);
        var bridge = new FakeBridge(Sample.Events()) { Kitchen = KitchenSample.Snapshot(), KitchenNow = now };
        var window = UiFixture.Open(Sample.Store(), () => now, bridge);
        var panel = window.KitchenPanel;
        void Tick(DateTimeOffset at)
        {
            now = at;
            Assert.True(window.KitchenTickAsync().IsCompletedSuccessfully);
        }
        try
        {
            window.ShowSection("kitchen");
            UiFixture.Settle();
            Assert.True(panel.SetItemGotAsync("koil00000002", got: true).IsCompletedSuccessfully);
            Assert.Equal("1 bottle · Got today", Row(panel, "Olive oil").Detail);
            Assert.Equal(["Tonight", "Thu Turkey chili"], DayRows(window)[..2]);
            Assert.Equal(Thursday, panel.SelectedDate);
            var revision = bridge.Kitchen.Revision;

            // Ten past midnight, and nothing changed on the server: the timer's fetch still draws the new day.
            Tick(Sample.Local(2026, 10, 30, 0, 10));
            Assert.Equal(revision, panel.Snapshot!.Revision);
            Assert.Equal(["Tonight", "Fri Leftover turkey chili"], DayRows(window)[..2]);
            Assert.Contains("Thu Turkey chili", DayRows(window));
            // Tonight was open, so the new tonight is; a rating now is Friday's.
            Assert.Equal(Thursday.AddDays(1), panel.SelectedDate);
            Assert.Equal("Tonight · Friday, October 30 · Leftovers", panel.DayWhen.Text);
            Toggle(panel.RatingOk);
            UiFixture.Settle();
            Assert.Equal("note 2026-10-30 rating=ok", bridge.Calls[^2]);
            Assert.Equal("1 bottle · Got yesterday", Row(panel, "Olive oil").Detail);

            // A day someone opened stays open over midnight.
            panel.SelectDay(Thursday.AddDays(-2));
            Tick(Sample.Local(2026, 10, 31, 0, 5));
            Assert.Equal(["Tonight", "Sat Sheet-pan lemon chicken"], DayRows(window)[..2]);
            Assert.Equal(Thursday.AddDays(-2), panel.SelectedDate);

            // Days later, what was bought more than seven days ago has left Got it.
            Assert.Contains("Milk", ListRows(window));
            Tick(Sample.Local(2026, 11, 4, 12, 0));
            Assert.DoesNotContain("Milk", ListRows(window));
            Assert.Equal(["Tonight", "Wed Black bean tacos"], DayRows(window)[..2]);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void TheServersNewWeekIsDrawnWithoutANewRevision() => ui.Run(() =>
    {
        var (window, bridge) = OpenKitchen();
        var panel = window.KitchenPanel;
        try
        {
            // The server's own date moved on to next week's plan; the revision did not change.
            bridge.Kitchen.Week = bridge.Kitchen.NextWeek;
            bridge.Kitchen.NextWeek = null;
            bridge.Kitchen.Today = new DateOnly(2026, 11, 3);
            Assert.True(panel.LoadAsync().IsCompletedSuccessfully);
            Assert.Equal(["This week", "Tue Turkey meatballs", "Wed Black bean tacos"], DayRows(window));

            // Its date alone is enough as well.
            var days = panel.DayList.ItemsSource;
            bridge.Kitchen.Today = new DateOnly(2026, 11, 4);
            Assert.True(panel.LoadAsync().IsCompletedSuccessfully);
            Assert.NotSame(days, panel.DayList.ItemsSource);

            // Nothing new: nothing drawn.
            days = panel.DayList.ItemsSource;
            Assert.True(panel.LoadAsync().IsCompletedSuccessfully);
            Assert.Same(days, panel.DayList.ItemsSource);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void AnUnsavedDinnerNoteIsKeptForItsDay() => ui.Run(() =>
    {
        var (window, bridge) = OpenKitchen();
        var panel = window.KitchenPanel;
        try
        {
            panel.DinnerNoteBox.Text = "Double the cumin";
            Assert.True(panel.SaveNoteButton.IsEnabled);

            // Tuesday shows its own note; back on Thursday, what was typed is still there, still to save.
            panel.SelectDay(Thursday.AddDays(-2));
            Assert.Equal("Less dill next time", panel.DinnerNoteBox.Text);
            Assert.False(panel.SaveNoteButton.IsEnabled);
            panel.DinnerNoteBox.Text = "Less dill, more lemon";
            panel.DayList.SelectedItem = panel.DayList.Items.OfType<MealDayRow>().Single(row => row.Day.Date == Thursday);
            Assert.Equal("Double the cumin", panel.DinnerNoteBox.Text);
            Assert.True(panel.SaveNoteButton.IsEnabled);

            // A refresh with news keeps it.
            bridge.Kitchen.Revision++;
            Assert.True(panel.LoadAsync().IsCompletedSuccessfully);
            Assert.Equal("Double the cumin", panel.DinnerNoteBox.Text);

            // Saved, it is no longer a draft; Tuesday's still is.
            Click(panel.SaveNoteButton);
            UiFixture.Settle();
            Assert.Equal("note 2026-10-29 note=Double the cumin", bridge.Calls[^2]);
            Assert.False(panel.SaveNoteButton.IsEnabled);
            panel.SelectDay(Thursday.AddDays(-2));
            Assert.Equal("Less dill, more lemon", panel.DinnerNoteBox.Text);
            Assert.True(panel.SaveNoteButton.IsEnabled);

            // Typed back to what is saved, it is no draft either.
            panel.DinnerNoteBox.Text = "Less dill next time";
            panel.SelectDay(Thursday);
            Assert.Equal("Double the cumin", panel.DinnerNoteBox.Text);
            panel.SelectDay(Thursday.AddDays(-2));
            Assert.Equal("Less dill next time", panel.DinnerNoteBox.Text);
            Assert.False(panel.SaveNoteButton.IsEnabled);
        }
        finally { window.Close(); }
    });

    // ------------------------------------------------------------ requests that overlap

    [Fact]
    public void AFetchFromBeforeAnEditNeverUndoesIt() => ui.Run(() =>
    {
        var (window, bridge) = OpenKitchen(part: KitchenView.ListPartName);
        var panel = window.KitchenPanel;
        try
        {
            bridge.HoldFetches = true;
            // A refresh goes out; before it answers, the foil is ticked off.
            Click(panel.RefreshButton);
            Assert.Equal(1, bridge.HeldFetches);
            Toggle(Control<CheckBox>(panel.ShoppingItems, row => row is ShoppingRow { Name: "Aluminum foil" }));
            UiFixture.Settle();
            Assert.Equal("item kfoil0000001 got", bridge.Calls[^1]);
            Assert.True(Row(panel, "Aluminum foil").IsGot);

            // The refresh answers with the list from before the tick: that answer is not shown...
            bridge.ReleaseFetch();
            UiFixture.Settle();
            Assert.True(Row(panel, "Aluminum foil").IsGot);
            // ...and the fetch the edit asked for follows it.
            Assert.Equal(1, bridge.HeldFetches);
            bridge.ReleaseFetch();
            UiFixture.Settle();
            Assert.True(Row(panel, "Aluminum foil").IsGot);
            Assert.Equal(0, bridge.HeldFetches);
            Assert.Equal(["kitchen", "kitchen", "item kfoil0000001 got", "kitchen"], bridge.Calls);

            // Fetches asked for while one is out (the timer, Refresh) ask for nothing more.
            var fetching = panel.LoadAsync();
            Assert.True(panel.LoadAsync().IsCompletedSuccessfully);
            Click(panel.RefreshButton);
            Assert.Equal(1, bridge.HeldFetches);
            bridge.ReleaseFetch();
            UiFixture.Settle();
            Assert.True(fetching.IsCompletedSuccessfully);
            Assert.Equal(0, bridge.HeldFetches);
            Assert.Equal(4, bridge.Calls.Count(call => call == "kitchen"));
        }
        finally { window.Close(); }
    });

    [Fact]
    public void UndoIsNeverSwallowedAndAReloadFromBeforeItIsNotShown() => ui.Run(() =>
    {
        var (window, bridge) = OpenKitchen(part: KitchenView.ListPartName);
        var panel = window.KitchenPanel;
        try
        {
            bridge.HoldFetches = true;

            // The salmon on order is removed; the fetch after it is slow, and Undo is clicked before it answers.
            Click(Control<Button>(panel.ShoppingItems, row => row is ShoppingRow { Name: "Salmon fillets" }));
            UiFixture.Settle();
            Assert.Equal(["remove ksalmon00004", "kitchen"], bridge.Calls[^2..]);
            Assert.DoesNotContain("Salmon fillets", ListRows(window));
            Click(window.ActionBarLink);
            UiFixture.Settle();
            Assert.Equal("add Salmon fillets as ordered [Walmart #200000000001, Mon 11/2]", bridge.Calls[^1]);
            Assert.Equal(ShoppingStatus.Ordered, Row(panel, "Salmon fillets").Item.Status);
            // That fetch answers with the list from before the Undo, without the salmon: not shown.
            bridge.ReleaseFetch();
            UiFixture.Settle();
            Assert.Equal(ShoppingStatus.Ordered, Row(panel, "Salmon fillets").Item.Status);
            // The fetch the Undo asked for follows, and has it.
            Assert.Equal(1, bridge.HeldFetches);
            bridge.ReleaseFetch();
            UiFixture.Settle();
            Assert.Equal(ShoppingStatus.Ordered, Row(panel, "Salmon fillets").Item.Status);
            Assert.Equal(0, bridge.HeldFetches);

            // An add whose fetch afterwards is slow is still busy; an Undo meanwhile still goes.
            var adding = panel.AddItemAsync("Dish soap");
            Assert.False(adding.IsCompleted);
            Click(Control<Button>(panel.ShoppingItems, row => row is ShoppingRow { Name: "Sweet potatoes" }));
            UiFixture.Settle();
            Assert.Equal("remove kpotato00005", bridge.Calls[^1]);
            Click(window.ActionBarLink);
            UiFixture.Settle();
            Assert.Equal("add Sweet potatoes as ordered [Walmart #200000000001, Mon 11/2]", bridge.Calls[^1]);
            while (bridge.HeldFetches > 0)
            {
                bridge.ReleaseFetch();
                UiFixture.Settle();
                Assert.Contains("Sweet potatoes", ListRows(window));
                Assert.Contains("Dish soap", ListRows(window));
            }
            Assert.True(adding.IsCompletedSuccessfully);
            Assert.Equal(["On order (2)", "Salmon fillets", "Sweet potatoes"], ListRows(window)[5..8]);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void TheKitchenTimerKeepsGoingAfterATickThatFails() => ui.Run(() =>
    {
        var bridge = new FakeBridge(Sample.Events()) { Kitchen = KitchenSample.Snapshot() };
        var now = KitchenSample.Now;
        var window = UiFixture.Open(Sample.Store(), () => now, bridge);
        try
        {
            window.ShowSection("kitchen");
            UiFixture.Settle();
            Assert.Equal(MainWindow.KitchenIdleRefresh, window.KitchenTimerDue);

            // A week the tab cannot draw: drawing it throws, and the tick with it...
            bridge.Kitchen.Week = bridge.Kitchen.Week! with { Days = null! };
            bridge.Kitchen.Revision++;
            now += MainWindow.KitchenIdleRefresh;
            Assert.True(window.KitchenTimerTickAsync().IsCompletedSuccessfully);
            // ...and the next tick is set all the same.
            Assert.Equal(MainWindow.KitchenIdleRefresh, window.KitchenTimerDue);

            // Put right on the server, the next tick draws it.
            bridge.Kitchen.Week = KitchenSample.Snapshot().Week;
            bridge.Kitchen.Revision++;
            now += MainWindow.KitchenIdleRefresh;
            Assert.True(window.KitchenTimerTickAsync().IsCompletedSuccessfully);
            Assert.Equal("Turkey chili", Text(window.KitchenPanel.DayDinner));
            Assert.Equal(MainWindow.KitchenIdleRefresh, window.KitchenTimerDue);

            // Another tab: no tick is set.
            window.ShowSection("all");
            Assert.Null(window.KitchenTimerDue);
        }
        finally { window.Close(); }
    });

    // ------------------------------------------------------------ corrections sent twice, items gone

    [Fact]
    public void ANoteSentAgainAfterNoAnswerIsStoredOnce() => ui.Run(() =>
    {
        var (window, bridge) = OpenKitchen(part: KitchenView.PantryPartName);
        var panel = window.KitchenPanel;
        try
        {
            // Stored, but the answer is lost on the way back.
            bridge.LoseNextCorrectionAnswer = new TaskCanceledException("The request was canceled due to the configured HttpClient.Timeout of 35 seconds elapsing.");
            panel.TellBox.Text = "Tossed the parsley";
            Click(panel.SendButton);
            UiFixture.Settle();
            Assert.Equal("Could not send it to Claude: The calendar service did not answer in time.", panel.LastProblem);
            Assert.Equal("Tossed the parsley", panel.TellBox.Text);

            // Sent again: the same client id, so the server keeps one.
            Click(panel.SendButton);
            UiFixture.Settle();
            Assert.Equal("", panel.TellBox.Text);
            Assert.Equal(2, bridge.CorrectionClientIds.Count);
            Assert.Equal(bridge.CorrectionClientIds[0], bridge.CorrectionClientIds[1]);
            Assert.Single(bridge.Kitchen.Corrections, item => item.Text == "Tossed the parsley");
            Assert.Single(panel.Snapshot!.Corrections, item => item.Text == "Tossed the parsley");

            // The same words again, as a new note: a new id, and a second note.
            panel.TellBox.Text = "Tossed the parsley";
            Click(panel.SendButton);
            UiFixture.Settle();
            Assert.NotEqual(bridge.CorrectionClientIds[0], bridge.CorrectionClientIds[2]);
            Assert.Equal(2, bridge.Kitchen.Corrections.Count(item => item.Text == "Tossed the parsley"));

            // A note changed after a failure is a new one.
            bridge.Failure = FakeBridge.Offline();
            panel.TellBox.Text = "Out of rice";
            Click(panel.SendButton);
            bridge.Failure = null;
            panel.TellBox.Text = "Out of brown rice";
            Click(panel.SendButton);
            UiFixture.Settle();
            Assert.NotEqual(bridge.CorrectionClientIds[3], bridge.CorrectionClientIds[4]);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void ANoteAboutAnItemClaudeRemovedStillReachesClaude() => ui.Run(() =>
    {
        var (window, bridge) = OpenKitchen(part: KitchenView.PantryPartName);
        var panel = window.KitchenPanel;
        try
        {
            // Bridge 0.11.0 keeps a note about an item that is gone, without the item.
            panel.StartNoteAbout("pcumin000009");
            panel.TellBox.Text += "almost gone";
            bridge.Kitchen.Pantry.RemoveAll(item => item.Id == "pcumin000009");
            Click(panel.SendButton);
            UiFixture.Settle();
            Assert.Equal("correction note pcumin000009 \"Cumin: almost gone\"", bridge.Calls[^2]);
            Assert.Null(bridge.Kitchen.Corrections.Single(item => item.Text == "Cumin: almost gone").ItemId);
            Assert.Equal("", panel.TellBox.Text);

            // Bridge 0.10.0 refuses it (404): no message box; the kitchen is fetched again, and the
            // note stays in the box, a plain one now, to send again.
            bridge.OldBridge = true;
            panel.StartNoteAbout("psalsa000007");
            panel.TellBox.Text += "the hot one";
            bridge.Kitchen.Pantry.RemoveAll(item => item.Id == "psalsa000007");
            Click(panel.SendButton);
            UiFixture.Settle();
            Assert.Equal(["correction note psalsa000007 \"Salsa: the hot one\"", "kitchen"], bridge.Calls[^2..]);
            Assert.Equal(0, window.KitchenProblemsReported);
            Assert.Equal("Could not send it to Claude: it was changed or removed elsewhere.", window.SyncStatus.Text);
            Assert.DoesNotContain(OnHandRows(panel), row => row.Name == "Salsa");
            Assert.Equal("Salsa: the hot one", panel.TellBox.Text);
            Assert.Null(panel.TellItemId);
            Assert.Equal(Visibility.Collapsed, panel.AboutItem.Visibility);
            Click(panel.SendButton);
            UiFixture.Settle();
            Assert.Equal("correction note \"Salsa: the hot one\"", bridge.Calls[^2]);
            Assert.Equal("", panel.TellBox.Text);
        }
        finally { window.Close(); }
    });
}
