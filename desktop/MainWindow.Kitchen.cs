using System.Windows;
using System.Windows.Threading;
using AICalendar.Desktop.Xp;

namespace AICalendar.Desktop;

/// <summary>
/// The window's side of the Kitchen tab: when it is fetched, and what it may use of the window.
/// There is no push on the PC, so while the tab is on screen it is fetched every 20 seconds when
/// the window is active, every 60 seconds otherwise, and once when the window is activated.
/// </summary>
public partial class MainWindow : IKitchenHost
{
    internal static readonly TimeSpan KitchenActiveRefresh = TimeSpan.FromSeconds(20);
    internal static readonly TimeSpan KitchenIdleRefresh = TimeSpan.FromSeconds(60);

    /// <summary>Activation, a restore and a show come together; a fetch this recent covers all of them.</summary>
    internal static readonly TimeSpan KitchenRecentFetch = TimeSpan.FromSeconds(3);

    private readonly DispatcherTimer _kitchenTimer = new();

    /// <summary>Whether the window has the focus. Tests replace it: their windows never take the focus.</summary>
    internal Func<bool> IsWindowActive { get; set; } = () => false;

    /// <summary>The Kitchen tab is what the user sees: chosen, and the window open and not minimized.</summary>
    private bool KitchenOnScreen => _section == Kitchen && IsLoaded && IsVisible && WindowState != WindowState.Minimized;

    /// <summary>How often the Kitchen tab is fetched now; null while it is not on screen.</summary>
    internal TimeSpan? KitchenCadence =>
        KitchenOnScreen && !_lifetime.IsCancellationRequested ? IsWindowActive() ? KitchenActiveRefresh : KitchenIdleRefresh : null;

    /// <summary>How long until the next timed fetch; null while the tab is not on screen.</summary>
    internal TimeSpan? KitchenRefreshWait
    {
        get
        {
            if (KitchenCadence is not { } cadence) return null;
            var wait = (KitchenPanel.FetchedAt ?? Now) + cadence - Now;
            return wait > TimeSpan.FromSeconds(1) ? wait : TimeSpan.FromSeconds(1);
        }
    }

    private void SetUpKitchen()
    {
        KitchenPanel.Host = this;
        KitchenPanel.ShowPart(_view.KitchenPart);
        IsWindowActive = () => IsActive;
        _kitchenTimer.Tick += async (_, _) => await KitchenTimerTickAsync();
        Activated += async (_, _) => await RefreshKitchenIfShownAsync();
        Deactivated += (_, _) => ScheduleKitchenRefresh();
        IsVisibleChanged += async (_, _) => await RefreshKitchenIfShownAsync();
        StateChanged += async (_, _) => await RefreshKitchenIfShownAsync();
        Closed += (_, _) => _kitchenTimer.Stop();
    }

    /// <summary>
    /// When the kitchen timer is set to fire next, from now; null while it is stopped. Set for a
    /// window a test opened too, though its timer never runs.
    /// </summary>
    internal TimeSpan? KitchenTimerDue { get; private set; }

    /// <summary>
    /// The kitchen timer fired. Whatever goes wrong in the tick, the next one is set, so the tab
    /// never stops refreshing.
    /// </summary>
    internal async Task KitchenTimerTickAsync()
    {
        _kitchenTimer.Stop();
        KitchenTimerDue = null;
        try
        {
            await KitchenTickAsync();
        }
        catch (Exception exception)
        {
            if (!_lifetime.IsCancellationRequested) _store.Log($"Kitchen refresh failed: {exception.GetType().Name}");
        }
        finally
        {
            ScheduleKitchenRefresh();
        }
    }

    /// <summary>The kitchen timer's work: a fetch once the cadence has passed since the last one, whatever started that.</summary>
    internal async Task KitchenTickAsync()
    {
        // Half a second early still counts: a timer can fire a hair before its time.
        if (KitchenCadence is { } cadence && (KitchenPanel.FetchedAt is not { } fetched || Now - fetched >= cadence - TimeSpan.FromMilliseconds(500)))
            await FetchKitchenAsync();
        else ScheduleKitchenRefresh();
    }

    /// <summary>The window was activated, shown or restored: fetch the kitchen once if its tab is on screen.</summary>
    internal async Task RefreshKitchenIfShownAsync()
    {
        if (KitchenOnScreen && (KitchenPanel.FetchedAt is not { } fetched || Now - fetched >= KitchenRecentFetch))
            await FetchKitchenAsync();
        else ScheduleKitchenRefresh();
    }

    private async Task FetchKitchenAsync(bool force = false)
    {
        try
        {
            await KitchenPanel.LoadAsync(force);
        }
        finally
        {
            ScheduleKitchenRefresh();
        }
    }

    /// <summary>Sets the timer for the next fetch, or stops it when the tab is not on screen. A window a test opened runs no timer.</summary>
    private void ScheduleKitchenRefresh()
    {
        _kitchenTimer.Stop();
        KitchenTimerDue = KitchenRefreshWait;
        if (!_background || KitchenTimerDue is not { } wait) return;
        _kitchenTimer.Interval = wait;
        _kitchenTimer.Start();
    }

    /// <summary>Sync Now and F5: the reminders, and on the Kitchen tab the kitchen too.</summary>
    private async Task SyncNowAsync()
    {
        var kitchen = _section == Kitchen ? FetchKitchenAsync(force: true) : Task.CompletedTask;
        await SyncAsync(showActivity: true);
        await kitchen;
    }

    BridgeClient IKitchenHost.Client => _client;

    bool IKitchenHost.IsPaired => Paired;

    DateTimeOffset IKitchenHost.Now => Now;

    CancellationToken IKitchenHost.Lifetime => _lifetime.Token;

    void IKitchenHost.SetStatus(string text) => SetStatus(text);

    void IKitchenHost.ShowActionBar(string text, Func<Task>? undo) => ShowActionBar(text, undo);

    /// <summary>
    /// A message box in the app's own window. A window a test opened shows nothing, as it shows no
    /// balloons; the reason is kept in <see cref="KitchenView.LastProblem"/>.
    /// </summary>
    void IKitchenHost.ReportKitchenProblem(string heading, string detail)
    {
        KitchenProblemsReported++;
        if (_background) XpMessageBox.Show(this, "Kitchen", $"{heading}.\n\n{detail}", XpMessageIcon.Warning);
    }

    /// <summary>How many kitchen problems got a message box (in a test's window, would have).</summary>
    internal int KitchenProblemsReported { get; private set; }

    void IKitchenHost.Log(string message) => _store.Log(message);

    void IKitchenHost.KitchenChanged()
    {
        if (_section == Kitchen) CountText.Text = KitchenPanel.CountText;
    }

    void IKitchenHost.KitchenPartChanged(string part)
    {
        _view.KitchenPart = part;
        _store.SaveView(_view);
    }
}
