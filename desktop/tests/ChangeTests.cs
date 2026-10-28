using System.Net;
using System.Windows;
using System.Windows.Controls.Primitives;

namespace AICalendar.Desktop.Tests;

/// <summary>Done, Undo, Delete and Sync against a stand-in for the calendar service.</summary>
[Collection(UiCollection.Name)]
public sealed class ChangeTests(UiFixture ui)
{
    private static string[] Ids(MainWindow window) => window.ReminderList.Items.OfType<ReminderRow>().Select(row => row.Event.Id).ToArray();

    private static void Click(ButtonBase button) => button.RaiseEvent(new RoutedEventArgs(ButtonBase.ClickEvent));

    [Fact]
    public void DoneMovesAReminderToArchivedAndUndoBringsItBack() => ui.Run(() =>
    {
        var bridge = new FakeBridge(Sample.Events());
        var window = UiFixture.Open(Sample.Store(), bridge: bridge);
        try
        {
            Assert.True(window.ChangeDoneAsync("dentist", done: true).IsCompletedSuccessfully);
            UiFixture.Settle();
            Assert.Equal(["done dentist True", "sync"], bridge.Calls);
            Assert.Equal("Life (4)", window.LifeTab.Content);
            Assert.DoesNotContain("dentist", Ids(window));
            Assert.Equal(Visibility.Visible, window.ActionBar.Visibility);
            Assert.Equal("Marked done: Dentist cleaning", window.ActionBarText.Text);
            Assert.Equal(Visibility.Visible, window.ActionBarLink.Visibility);

            Click(window.ActionBarLink);
            UiFixture.Settle();
            Assert.Equal(["done dentist True", "sync", "done dentist False", "sync"], bridge.Calls);
            Assert.Equal("Life (5)", window.LifeTab.Content);
            Assert.Contains("dentist", Ids(window));
            Assert.Equal("Marked not done: Dentist cleaning", window.ActionBarText.Text);
            // Taking it back is not itself offered for undo.
            Assert.Equal(Visibility.Collapsed, window.ActionBarLink.Visibility);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void AFailedChangeLeavesTheReminderAndSaysWhy() => ui.Run(() =>
    {
        var bridge = new FakeBridge(Sample.Events()) { Failure = FakeBridge.Offline() };
        var window = UiFixture.Open(Sample.Store(), bridge: bridge);
        try
        {
            Assert.True(window.ChangeDoneAsync("dentist", done: true).IsCompletedSuccessfully);
            UiFixture.Settle();
            Assert.Equal(["done dentist True"], bridge.Calls);
            Assert.Equal("Life (5)", window.LifeTab.Content);
            Assert.False(window.ReminderList.Items.OfType<ReminderRow>().Single(row => row.Event.Id == "dentist").IsDone);
            Assert.Equal(Visibility.Collapsed, window.ActionBar.Visibility);
            Assert.Equal(Visibility.Visible, window.IssueBar.Visibility);
            Assert.Equal("Could not update the reminder: No such host is known.", window.IssueBarText.Text);

            // The same reminder can be tried again once the service answers.
            bridge.Failure = null;
            Assert.True(window.ChangeDoneAsync("dentist", done: true).IsCompletedSuccessfully);
            Assert.Equal("Life (4)", window.LifeTab.Content);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void DeleteRemovesTheReminder() => ui.Run(() =>
    {
        var bridge = new FakeBridge(Sample.Events());
        var window = UiFixture.Open(Sample.Store(), bridge: bridge);
        try
        {
            Assert.True(window.DeleteAsync("oil").IsCompletedSuccessfully);
            UiFixture.Settle();
            Assert.Equal(["delete oil", "sync"], bridge.Calls);
            Assert.Equal("Life (4)", window.LifeTab.Content);
            Assert.Equal("Deleted: Oil change", window.ActionBarText.Text);
            Assert.Equal(Visibility.Collapsed, window.ActionBarLink.Visibility);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void SyncShowsWhatTheServiceHasAndKeepsItForOffline() => ui.Run(() =>
    {
        var store = Sample.Store(events: []);
        var bridge = new FakeBridge(Sample.Events());
        var window = UiFixture.Open(store, bridge: bridge);
        try
        {
            Assert.Equal("Life (0)", window.LifeTab.Content);
            Assert.True(window.SyncAsync(showActivity: true).IsCompletedSuccessfully);
            UiFixture.Settle();
            Assert.Equal("Life (5)", window.LifeTab.Content);
            Assert.Equal("Synced 2:05 PM", window.SyncStatus.Text);
            Assert.Equal("Cloud: connected", window.CloudText.Text);
            Assert.Equal(18, store.LoadEvents().Count);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void AFailedSyncKeepsTheListAndItsNoticeStaysClosedOnceClosed() => ui.Run(() =>
    {
        var bridge = new FakeBridge(Sample.Events()) { Failure = FakeBridge.Offline() };
        var window = UiFixture.Open(Sample.Store(), bridge: bridge);
        try
        {
            Assert.True(window.SyncAsync(showActivity: false).IsCompletedSuccessfully);
            Assert.Equal("Life (5)", window.LifeTab.Content);
            Assert.Equal("Cloud: needs attention", window.CloudText.Text);
            Assert.Equal(Visibility.Visible, window.IssueBar.Visibility);
            Assert.Equal("Could not sync: No such host is known.", window.IssueBarText.Text);

            // Closed by the user: the next failed poll, a minute later, does not bring it back.
            Click(VisualTreeOf.Button(window.IssueBar));
            Assert.Equal(Visibility.Collapsed, window.IssueBar.Visibility);
            Assert.True(window.SyncAsync(showActivity: false).IsCompletedSuccessfully);
            Assert.Equal(Visibility.Collapsed, window.IssueBar.Visibility);
            Assert.Equal("Cloud: needs attention", window.CloudText.Text);

            // Back online.
            bridge.Failure = null;
            Assert.True(window.SyncAsync(showActivity: false).IsCompletedSuccessfully);
            Assert.Equal("Cloud: connected", window.CloudText.Text);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void ARejectedPairingIsReportedAsNeedingAttention() => ui.Run(() =>
    {
        var bridge = new FakeBridge(Sample.Events()) { Failure = new BridgeException(HttpStatusCode.Unauthorized, "bad token") };
        var window = UiFixture.Open(Sample.Store(), bridge: bridge);
        try
        {
            Assert.True(window.SyncAsync(showActivity: false).IsCompletedSuccessfully);
            Assert.Equal("Could not sync: This PC is no longer authorized. Pair it again under Tools > Cloud Connection.", window.IssueBarText.Text);
        }
        finally { window.Close(); }
    });
}

internal static class VisualTreeOf
{
    public static ButtonBase Button(DependencyObject parent) =>
        AICalendar.Desktop.Xp.VisualTree.Descendant<ButtonBase>(parent) ?? throw new InvalidOperationException("No button inside.");
}
