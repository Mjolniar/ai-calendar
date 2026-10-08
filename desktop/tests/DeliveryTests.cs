using System.Windows;
using System.Windows.Controls.Primitives;
using System.Windows.Media;

namespace AICalendar.Desktop.Tests;

/// <summary>The Deliveries tab, and the promise that a delivery never alerts.</summary>
[Collection(UiCollection.Name)]
public sealed class DeliveryTests(UiFixture ui)
{
    private static string[] Rows(MainWindow window) => window.ReminderList.Items.Cast<object>().Select(row => row switch
    {
        GroupHeaderRow caption => $"{caption.Label} {caption.CountText}",
        ReminderRow reminder => reminder.Event.Id,
        _ => "?",
    }).ToArray();

    private static ReminderRow Row(MainWindow window, string id) =>
        window.ReminderList.Items.OfType<ReminderRow>().Single(row => row.Event.Id == id);

    [Fact]
    public void DeliveriesHaveTheirOwnTabAndStayOutOfLife() => ui.Run(() =>
    {
        var window = UiFixture.Open(Sample.Store(Sample.WithDeliveries()));
        try
        {
            Assert.Equal("Life (5)", window.LifeTab.Content);
            Assert.Equal("School (9)", window.SchoolTab.Content);
            Assert.Equal("Deliveries (3)", window.DeliveriesTab.Content);
            Assert.Equal("All (17)", window.AllTab.Content);
            Assert.DoesNotContain("lamp", Rows(window));

            window.ShowSection("delivery");
            UiFixture.Settle();
            Assert.True(window.DeliveriesTab.IsChecked);
            Assert.Equal(["Today (1)", "lamp", "This week (1)", "textbook", "Next week (1)", "jacket"], Rows(window));
            Assert.Equal("3 deliveries", window.CountText.Text);
            Assert.Equal(Visibility.Collapsed, window.ClassRow.Visibility);

            var lamp = Row(window, "lamp");
            Assert.Equal("Delivery - UPS", lamp.Secondary);
            Assert.Equal("9:00 AM", lamp.PrimaryWhen);
            // Its time has passed, but it is expected today: no "5 h ago".
            Assert.Equal("", lamp.SecondaryWhen);
            Assert.Contains(new DetailLine("Alerts", "None. Deliveries never alert."), lamp.Details);
            Assert.Contains(new DetailLine("Type", "Delivery - Cloud"), lamp.Details);
            Assert.Equal(Color.FromRgb(0xA9, 0x74, 0x4F), ((SolidColorBrush)lamp.Accent).Color);

            window.ShowSection("all");
            UiFixture.Settle();
            Assert.Contains("lamp", Rows(window));
            Assert.Contains("dentist", Rows(window));
            Assert.Equal("17 reminders", window.CountText.Text);

            window.ShowSection("archived");
            UiFixture.Settle();
            Assert.Contains("cable", Rows(window));
            Assert.DoesNotContain("lamp", Rows(window));
        }
        finally { window.Close(); }
    });

    [Fact]
    public void ClickingTheTabShowsTheDeliveriesAndIsRemembered() => ui.Run(() =>
    {
        var store = Sample.Store(Sample.WithDeliveries());
        var window = UiFixture.Open(store);
        try
        {
            window.DeliveriesTab.RaiseEvent(new RoutedEventArgs(ButtonBase.ClickEvent));
            UiFixture.Settle();
            Assert.True(window.DeliveriesTab.IsChecked);
            Assert.Equal(["Today (1)", "lamp", "This week (1)", "textbook", "Next week (1)", "jacket"], Rows(window));
            Assert.Equal("delivery", store.LoadView().Section);

            window.LifeTab.RaiseEvent(new RoutedEventArgs(ButtonBase.ClickEvent));
            UiFixture.Settle();
            Assert.True(window.LifeTab.IsChecked);
            Assert.Equal("5 reminders", window.CountText.Text);
            Assert.Equal("life", store.LoadView().Section);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void AnEmptyDeliveriesTabSaysSo() => ui.Run(() =>
    {
        var window = UiFixture.Open(Sample.Store());
        try
        {
            Assert.Equal("Deliveries (0)", window.DeliveriesTab.Content);
            window.ShowSection("delivery");
            UiFixture.Settle();
            Assert.Empty(Rows(window));
            Assert.Equal(Visibility.Visible, window.EmptyPanel.Visibility);
            Assert.Equal("No deliveries on the way.", window.EmptyText.Text);
            Assert.Equal("0 deliveries", window.CountText.Text);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void ADeliveryNeverAlertsWhateverLeadTimesItCarries() => ui.Run(() =>
    {
        // The same moment and the same lead times, once as a delivery and once as a reminder.
        var start = Sample.Local(2026, 10, 2, 13, 0);
        var parcel = Sample.Delivery("parcel", "Parcel", start, offsets: [1440, 60, 0]);
        var visit = Sample.Plain("visit", "Plumber visit", start, offsets: [1440, 60, 0]);
        var tagged = Sample.Plain("tagged", "Bookshelf", start, offsets: [1440, 60, 0]);
        tagged.Domain = "";
        tagged.Category = "delivery";
        var window = UiFixture.Open(Sample.Store([parcel, visit, tagged]), () => Sample.Local(2026, 10, 1, 12, 0));
        try
        {
            string[] Due(DateTimeOffset now) => window.TakeDueReminders(now).Select(due => $"{due.Event.Id}:{due.Kind}").Order().ToArray();

            Assert.Equal(["visit:offset_1440"], Due(Sample.Local(2026, 10, 1, 13, 0)));
            Assert.Equal(["visit:offset_60"], Due(Sample.Local(2026, 10, 2, 12, 0)));
            Assert.Equal(["visit:offset_0"], Due(Sample.Local(2026, 10, 2, 13, 0).AddSeconds(20)));
            // Checked at every one of those moments, the deliveries were never due. Nor at any other time.
            for (var minute = 0; minute <= 26 * 60; minute += 1)
            {
                Assert.DoesNotContain(window.TakeDueReminders(Sample.Local(2026, 10, 1, 12, 0).AddMinutes(minute)),
                    due => due.Event.Id is "parcel" or "tagged");
            }
            Assert.Equal("Deliveries (2)", window.DeliveriesTab.Content);
            Assert.Equal("Life (1)", window.LifeTab.Content);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void ADeliveryIsFiledWhenItsDayEndsNotAtItsTime() => ui.Run(() =>
    {
        var now = Sample.Now;
        var morningCall = Sample.Plain("call", "Morning call", Sample.Local(2026, 10, 1, 9, 0));
        var window = UiFixture.Open(Sample.Store([.. Sample.Deliveries(), morningCall]), () => now);
        try
        {
            // A reminder from 9 AM is already archived at 2:05 PM; the delivery expected today is not.
            Assert.Equal("Life (0)", window.LifeTab.Content);
            Assert.Equal("Deliveries (3)", window.DeliveriesTab.Content);

            now = Sample.Local(2026, 10, 1, 23, 59);
            window.Render();
            Assert.Equal("Deliveries (3)", window.DeliveriesTab.Content);

            now = Sample.Local(2026, 10, 2, 0, 0).AddSeconds(30);
            window.Render();
            UiFixture.Settle();
            Assert.Equal("Deliveries (2)", window.DeliveriesTab.Content);
            window.ShowSection("archived");
            UiFixture.Settle();
            Assert.Equal(["Yesterday (2)", "lamp", "call", "Earlier this week (1)", "cable"], Rows(window));
        }
        finally { window.Close(); }
    });

    [Fact]
    public void OpeningADeliveryKeepsTheDeliveriesTabOrFallsBackToAll() => ui.Run(() =>
    {
        var window = UiFixture.Open(Sample.Store(Sample.WithDeliveries(), view: new ViewSettings { Section = "delivery" }));
        try
        {
            // The tab that was open last time is remembered.
            Assert.True(window.DeliveriesTab.IsChecked);

            window.OpenReminder("jacket");
            UiFixture.Settle();
            Assert.True(window.DeliveriesTab.IsChecked);
            Assert.Equal("jacket", (window.ReminderList.SelectedItem as ReminderRow)?.Event.Id);

            // A reminder that is not a delivery cannot be shown here: All has everything.
            window.OpenReminder("rent");
            UiFixture.Settle();
            Assert.True(window.AllTab.IsChecked);

            window.ShowSection("life");
            window.OpenReminder("textbook");
            UiFixture.Settle();
            Assert.True(window.AllTab.IsChecked);
            Assert.Equal("textbook", (window.ReminderList.SelectedItem as ReminderRow)?.Event.Id);

            window.OpenReminder("cable");
            UiFixture.Settle();
            Assert.True(window.ArchivedTab.IsChecked);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void ReceivingADeliveryTakesItOffTheTab() => ui.Run(() =>
    {
        var bridge = new FakeBridge(Sample.WithDeliveries());
        var window = UiFixture.Open(Sample.Store(Sample.WithDeliveries()), bridge: bridge);
        try
        {
            window.ShowSection("delivery");
            Assert.True(window.ChangeDoneAsync("lamp", done: true).IsCompletedSuccessfully);
            UiFixture.Settle();
            Assert.Equal(["done lamp True", "sync"], bridge.Calls);
            Assert.Equal("Deliveries (2)", window.DeliveriesTab.Content);
            Assert.Equal("Marked done: Desk lamp", window.ActionBarText.Text);
            Assert.DoesNotContain("lamp", Rows(window));
        }
        finally { window.Close(); }
    });

    [Fact]
    public void TheSavedTabChoiceSurvivesForDeliveries()
    {
        Assert.Equal("delivery", new ViewSettings { Section = "delivery" }.Normalize().Section);
        Assert.Equal("life", new ViewSettings { Section = "parcels" }.Normalize().Section);
    }
}
