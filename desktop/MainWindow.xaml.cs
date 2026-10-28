using System.ComponentModel;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Threading;
using AICalendar.Desktop.Xp;
using Forms = System.Windows.Forms;

namespace AICalendar.Desktop;

/// <summary>
/// The main window: menu bar, toolbar and tabs over the reminder list, with a month view beside it.
/// It also runs the background work: a sync every minute and the reminder balloons.
/// </summary>
public partial class MainWindow : XpWindow
{
    // The three feed tabs are named after the bridge's feeds, so a tab lists the reminders whose
    // resolved feed has its name.
    private const string Life = "life";
    private const string School = "school";
    private const string Deliveries = "delivery";
    private const string All = "all";
    private const string Archived = "archived";
    private const string Kitchen = "kitchen";

    /// <summary>A reminder still pops up when its time passed this recently; the check runs every 30 seconds.</summary>
    private const int ReminderGraceMinutes = 2;

    /// <summary>Above this many remembered reminders, the ones of past events are dropped.</summary>
    private const int FiredKeysLimit = 2000;

    private readonly SettingsStore _store;
    private readonly bool _background;
    private readonly Func<DateTimeOffset> _clock;
    private readonly Func<DesktopSettings, BridgeClient> _connect;
    private readonly ViewSettings _view;
    private readonly HashSet<string> _busy = [];
    private readonly CancellationTokenSource _lifetime = new();
    private readonly DispatcherTimer _syncTimer = new() { Interval = TimeSpan.FromSeconds(60) };
    private readonly DispatcherTimer _reminderTimer = new() { Interval = TimeSpan.FromSeconds(30) };
    private readonly DispatcherTimer _actionBarTimer = new() { Interval = TimeSpan.FromSeconds(8) };

    private DesktopSettings _settings;
    private BridgeClient _client;
    private List<CalendarEvent> _events;
    private Dictionary<string, ReminderSeries> _series;
    private List<object> _rows = [];
    private List<CourseChoice> _courses = [];
    private string _section;
    private string? _courseKey;
    private bool _calendarMode;
    private string _query = "";
    private string? _selectedId;
    private bool _revealSelection;
    private DateTime _visibleMonth;
    private DateTime _selectedDate;
    private string _monthSignature = "";

    /// <summary>True while code rebinds a list; the selection events it causes are not the user's.</summary>
    private bool _binding;
    private bool _syncing;
    private bool _resyncRequested;
    private bool _exiting;
    private bool _trayHintShown;
    private int _lastLoggedCount = -1;
    private DateTimeOffset? _lastSync;
    private string? _syncProblem;
    private string? _notice;
    private string? _dismissedNotice;
    private Func<Task>? _undo;
    private Action? _emptyAction;
    private ScrollViewer? _listScroll;
    private Forms.NotifyIcon? _notifyIcon;

    public MainWindow() : this(App.Store, background: true)
    {
    }

    /// <summary>
    /// With <paramref name="background"/> false the window does nothing on its own: no tray icon,
    /// timers, pairing prompt or balloons. The tests use that, with their own clock and a stand-in
    /// for the calendar service.
    /// </summary>
    internal MainWindow(SettingsStore store, bool background, Func<DateTimeOffset>? clock = null,
        Func<DesktopSettings, BridgeClient>? connect = null)
    {
        _store = store;
        _background = background;
        _clock = clock ?? (() => DateTimeOffset.Now);
        _connect = connect ?? (settings => new BridgeClient(settings));
        AskSeriesScope = AskSeriesScopeWithDialog;
        InitializeComponent();
        _settings = store.Load();
        _client = _connect(_settings);
        _view = store.LoadView();
        _events = Sorted(store.LoadEvents());
        _series = ToMap(store.LoadSeries());
        _section = _view.Section;
        _calendarMode = _view.CalendarMode;
        SetUpKitchen();
        _selectedDate = Now.LocalDateTime.Date;
        _visibleMonth = new DateTime(_selectedDate.Year, _selectedDate.Month, 1);
        RestorePlacement();
        _actionBarTimer.Tick += (_, _) => HideActionBar();
        Loaded += MainWindow_Loaded;
        Closing += MainWindow_Closing;
        Closed += MainWindow_Closed;
        IsVisibleChanged += (_, _) => RefreshIfShown();
        StateChanged += (_, _) => RefreshIfShown();
        if (!background) return;
        _syncTimer.Tick += SyncTimer_Tick;
        _reminderTimer.Tick += ReminderTimer_Tick;
        CreateTrayIcon();
    }

    /// <summary>True for the app's own window; false for one a test opened.</summary>
    internal bool RunsInBackground => _background;

    private DateTimeOffset Now => _clock();

    private bool Paired => !string.IsNullOrWhiteSpace(_settings.DeviceToken);

    private ScrollViewer? ListScroll => _listScroll ??= VisualTree.Descendant<ScrollViewer>(ReminderList);

    // ------------------------------------------------------------ start and stop

    private async void MainWindow_Loaded(object sender, RoutedEventArgs e)
    {
        RefreshCourses();
        UpdateCloudState();
        Render(viewChanged: true);
        if (_section == Kitchen && KitchenPanel.FetchedAt is null) _ = FetchKitchenAsync();
        if (!_background) return;
        if (!Paired)
        {
            if (_store.HasSavedSettings)
            {
                _store.Log("Startup: saved pairing unavailable; preserving settings, not prompting to replace it");
                SetStatus("The saved cloud connection could not be read.");
                return;
            }
            _store.Log("Startup: no saved settings; first-time pairing");
            if (!ShowConnection())
            {
                SetStatus("Not connected");
                return;
            }
        }
        StartBackgroundWork();
        await SyncAsync(showActivity: true);
    }

    private void StartBackgroundWork()
    {
        if (!_background) return;
        _syncTimer.Start();
        _reminderTimer.Start();
    }

    private async void SyncTimer_Tick(object? sender, EventArgs e) => await SyncAsync(showActivity: false);

    private void ReminderTimer_Tick(object? sender, EventArgs e)
    {
        CheckReminders();
        RefreshIfShown();
    }

    /// <summary>Keeps "in 45 min" current, and moves a reminder to Archived when its time passes.</summary>
    private void RefreshIfShown()
    {
        if (IsLoaded && IsVisible && WindowState != WindowState.Minimized) Render();
    }

    /// <summary>Opens the window where it was closed, as long as its caption would still be on a screen.</summary>
    private void RestorePlacement()
    {
        if (_view is { WindowWidth: { } width, WindowHeight: { } height } && width >= MinWidth && height >= MinHeight)
        {
            Width = width;
            Height = height;
        }
        if (_view is { WindowLeft: { } left, WindowTop: { } top })
        {
            var screens = new Rect(SystemParameters.VirtualScreenLeft, SystemParameters.VirtualScreenTop,
                SystemParameters.VirtualScreenWidth, SystemParameters.VirtualScreenHeight);
            if (screens.Contains(new Rect(left + 60, top, Math.Max(Width - 120, 1), 29)))
            {
                WindowStartupLocation = WindowStartupLocation.Manual;
                Left = left;
                Top = top;
            }
        }
        if (_view.WindowMaximized) WindowState = WindowState.Maximized;
    }

    private void SavePlacement()
    {
        var bounds = WindowState == WindowState.Normal ? new Rect(Left, Top, Width, Height) : RestoreBounds;
        if (bounds.IsEmpty || double.IsNaN(bounds.Left) || double.IsNaN(bounds.Width)) return;
        _view.WindowLeft = bounds.Left;
        _view.WindowTop = bounds.Top;
        _view.WindowWidth = bounds.Width;
        _view.WindowHeight = bounds.Height;
        _view.WindowMaximized = WindowState == WindowState.Maximized;
        _store.SaveView(_view);
    }

    private void MainWindow_Closing(object? sender, CancelEventArgs e)
    {
        if (_background) SavePlacement();
        if (_exiting || !_background || !_view.KeepInTray) return;
        e.Cancel = true;
        Hide();
        if (_trayHintShown) return;
        _trayHintShown = true;
        XpBalloon.Show("AI Planner is still running",
            "Reminders keep popping up here. Double-click the calendar icon in the notification area to open the window.",
            onClick: ShowFromTray, lifetime: TimeSpan.FromSeconds(10));
    }

    private void MainWindow_Closed(object? sender, EventArgs e)
    {
        // Stop background work before the tray icon it uses is disposed.
        _syncTimer.Stop();
        _reminderTimer.Stop();
        _actionBarTimer.Stop();
        _syncTimer.Tick -= SyncTimer_Tick;
        _reminderTimer.Tick -= ReminderTimer_Tick;
        _lifetime.Cancel();
        if (!_background) return;
        XpBalloon.CloseAll();
        if (_notifyIcon is null) return;
        _notifyIcon.Visible = false;
        _notifyIcon.Dispose();
        _notifyIcon = null;
    }

    private void Exit()
    {
        _exiting = true;
        Close();
    }

    // ------------------------------------------------------------ notification area

    private void CreateTrayIcon()
    {
        _notifyIcon = new Forms.NotifyIcon { Icon = LoadTrayIcon(), Text = "AI Planner", Visible = true };
        _notifyIcon.MouseDoubleClick += (_, args) =>
        {
            if (args.Button == Forms.MouseButtons.Left) ShowFromTray();
        };
        _notifyIcon.MouseUp += (_, args) =>
        {
            if (args.Button == Forms.MouseButtons.Right) ShowTrayMenu();
        };
    }

    private static System.Drawing.Icon LoadTrayIcon()
    {
        try
        {
            if (Application.GetResourceStream(new Uri("pack://application:,,,/AppIcon.ico")) is { } resource)
            {
                using var stream = resource.Stream;
                return new System.Drawing.Icon(stream, Forms.SystemInformation.SmallIconSize);
            }
        }
        catch (Exception exception) when (exception is IOException or ArgumentException or Win32Exception)
        {
        }
        return System.Drawing.SystemIcons.Application;
    }

    private void ShowFromTray()
    {
        Show();
        if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
        Activate();
    }

    private void ShowTrayMenu()
    {
        var menu = BuildTrayMenu();
        // The menu has to own the foreground, or it stays open when the user clicks somewhere else.
        menu.Opened += (_, _) =>
        {
            if (PresentationSource.FromVisual(menu) is HwndSource source) NativeMethods.BringToForeground(source.Handle);
        };
        menu.IsOpen = true;
    }

    /// <summary>The menu of the notification-area icon.</summary>
    internal ContextMenu BuildTrayMenu()
    {
        var menu = new ContextMenu { Placement = PlacementMode.MousePoint };
        menu.Items.Add(TrayItem("Open AI Planner", ShowFromTray, bold: true));
        var sync = TrayItem("Sync Now", () => _ = SyncAsync(showActivity: true));
        sync.IsEnabled = Paired;
        menu.Items.Add(sync);
        menu.Items.Add(new Separator());
        menu.Items.Add(TrayItem("Exit", Exit));
        return menu;
    }

    private static MenuItem TrayItem(string header, Action run, bool bold = false)
    {
        var item = new MenuItem { Header = header };
        if (bold) item.FontWeight = FontWeights.Bold;
        item.Click += (_, _) => run();
        return item;
    }

    // ------------------------------------------------------------ cloud

    internal async Task SyncAsync(bool showActivity)
    {
        if (_lifetime.IsCancellationRequested || !Paired) return;
        if (_syncing)
        {
            // A change was made while a poll was in flight; its result may predate the change.
            _resyncRequested = true;
            return;
        }
        _syncing = true;
        if (showActivity) SetStatus("Syncing...");
        try
        {
            var result = await _client.SyncAsync(_lifetime.Token);
            _events = Sorted(result.Events);
            try { _store.SaveEvents(_events); }
            catch (Exception exception) when (exception is IOException or UnauthorizedAccessException) { _store.Log($"Event cache write failed: {exception.GetType().Name}"); }
            if (result.Series is { } series)
            {
                _series = ToMap(series);
                try { _store.SaveSeries(series); }
                catch (Exception exception) when (exception is IOException or UnauthorizedAccessException) { _store.Log($"Series cache write failed: {exception.GetType().Name}"); }
            }
            if (_events.Count != _lastLoggedCount || result.SkippedEvents > 0)
            {
                _store.Log($"Sync succeeded: {_events.Count} events" + (result.SkippedEvents > 0 ? $", skipped {result.SkippedEvents} malformed" : ""));
                _lastLoggedCount = _events.Count;
            }
            _lastSync = Now;
            _syncProblem = null;
            _dismissedNotice = null;
            ShowNotice(null);
            SetStatus($"Synced {ReminderText.Time(_lastSync.Value)}" + (result.SkippedEvents > 0 ? $" - {result.SkippedEvents} unreadable skipped" : ""));
            RefreshCourses();
            Render();
            CheckReminders();
        }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested)
        {
        }
        catch (Exception exception)
        {
            _store.Log($"Sync failed: {exception.GetType().Name}{(exception is HttpRequestException { StatusCode: { } code } ? $" {(int)code}" : "")}");
            _lastLoggedCount = -1;
            if (!_lifetime.IsCancellationRequested)
            {
                _syncProblem = Friendly(exception);
                ShowNotice($"Could not sync: {_syncProblem}");
                SetStatus(_lastSync is { } synced ? $"Offline - last synced {ReminderText.Time(synced)}" : "Offline - showing the saved copy");
            }
        }
        finally
        {
            _syncing = false;
            if (!_lifetime.IsCancellationRequested)
            {
                UpdateCloudState();
                if (_resyncRequested)
                {
                    _resyncRequested = false;
                    _ = Dispatcher.InvokeAsync(() => SyncAsync(showActivity: false));
                }
            }
        }
    }

    /// <summary>Opens Tools > Cloud Connection. True when this PC is paired afterwards.</summary>
    private bool ShowConnection()
    {
        _store.Log("Cloud Connection opened");
        if (!Paired)
        {
            // The saved pairing may only have been unreadable when the app started.
            var saved = _store.Load();
            if (!string.IsNullOrWhiteSpace(saved.DeviceToken)) _settings = saved;
        }
        var state = new ConnectionState(Paired, !Paired && _store.HasSavedSettings, _lastSync, _syncProblem);
        var dialog = new ConnectionWindow(_settings, _store, state) { Owner = this };
        if (dialog.ShowDialog() == true)
        {
            _lastSync = null;
            _syncProblem = null;
            _dismissedNotice = null;
            ShowNotice(null);
        }
        _client = _connect(_settings);
        UpdateCloudState();
        Render();
        if (_section == Kitchen) _ = FetchKitchenAsync(force: true);
        return Paired;
    }

    private async Task OpenConnectionAsync()
    {
        if (!ShowConnection()) return;
        StartBackgroundWork();
        await SyncAsync(showActivity: true);
    }

    /// <summary>The status bar's cloud pane, the "not connected" bar and what needs a connection.</summary>
    private void UpdateCloudState()
    {
        var paired = Paired;
        ConnectBar.Visibility = paired ? Visibility.Collapsed : Visibility.Visible;
        ConnectBarText.Text = _store.HasSavedSettings
            ? "The saved cloud connection could not be read. Click to pair this PC again."
            : "Not connected to the cloud. Click to pair this PC.";
        SyncButton.IsEnabled = paired;
        SyncMenuItem.IsEnabled = paired;
        var (icon, label) = !paired ? ("Icon.CloudOff", "Cloud: not connected")
            : _syncProblem is not null ? ("Icon.CloudWarn", "Cloud: needs attention")
            : ("Icon.CloudOk", "Cloud: connected");
        CloudIcon.Source = (ImageSource)FindResource(icon);
        CloudText.Text = label;
    }

    private void SetStatus(string text) => SyncStatus.Text = text;

    /// <summary>Shows a problem in the bar above the list. A text the user closed stays closed while it repeats.</summary>
    internal void ShowNotice(string? text)
    {
        if (text is not null && text == _dismissedNotice) text = null;
        _notice = text;
        IssueBarText.Text = text ?? "";
        IssueBar.Visibility = text is null ? Visibility.Collapsed : Visibility.Visible;
    }

    private static List<CalendarEvent> Sorted(IEnumerable<CalendarEvent> events) =>
        events.OrderBy(item => item.Start).ThenBy(item => item.Id, StringComparer.Ordinal).ToList();

    private static string Friendly(Exception exception) =>
        exception.Message.Length > 110 ? exception.Message[..110] + "..." : exception.Message;

    // ------------------------------------------------------------ what is shown

    /// <summary>
    /// Brings every part of the window in line with the data, the clock and the view. Called often
    /// (after each sync and twice a minute), so a list that still reads the same is left alone.
    /// <paramref name="viewChanged"/> says the user asked for a different list: it starts at the top.
    /// </summary>
    internal void Render(bool viewChanged = false)
    {
        var now = Now;
        var today = DateOnly.FromDateTime(now.LocalDateTime);
        var archived = _section == Archived;
        var kitchen = _section == Kitchen;
        var searching = _query.Length > 0;
        // Find lists matches from every date, so it takes over from the month view.
        var canToggle = !archived && !searching && !kitchen;
        var showCalendar = _calendarMode && canToggle;

        var current = _events.Where(item => !item.IsArchivedAt(now)).ToList();
        var feeds = current.ToLookup(item => item.ResolvedDomain);
        LifeTab.Content = $"Life ({feeds[Life].Count()})";
        SchoolTab.Content = $"School ({feeds[School].Count()})";
        DeliveriesTab.Content = $"Deliveries ({feeds[Deliveries].Count()})";
        AllTab.Content = $"All ({current.Count})";
        TabFor(_section).IsChecked = true;

        ListModeButton.IsEnabled = CalendarModeButton.IsEnabled = canToggle;
        ListMenuItem.IsEnabled = CalendarMenuItem.IsEnabled = canToggle;
        ListModeButton.IsChecked = ListMenuItem.IsChecked = !showCalendar;
        CalendarModeButton.IsChecked = CalendarMenuItem.IsChecked = showCalendar;
        ClassRow.Visibility = _section == School && _courses.Count > 1 ? Visibility.Visible : Visibility.Collapsed;
        CalendarPanel.Visibility = showCalendar ? Visibility.Visible : Visibility.Collapsed;

        // The Kitchen tab has its own view; Find and the reminder list wait until another tab is chosen.
        KitchenPanel.Visibility = kitchen ? Visibility.Visible : Visibility.Collapsed;
        ListArea.Visibility = kitchen ? Visibility.Collapsed : Visibility.Visible;
        FindBox.IsEnabled = !kitchen;
        if (kitchen)
        {
            CountText.Text = KitchenPanel.CountText;
            return;
        }

        IEnumerable<CalendarEvent> matching = _section switch
        {
            Archived => ReminderText.ArchiveOrder(_events.Where(item => item.IsArchivedAt(now)), today),
            All => current,
            School or Deliveries => feeds[_section],
            _ => feeds[Life],
        };
        if (_section == School && _courseKey is not null) matching = matching.Where(item => item.CourseKey == _courseKey);
        if (searching) matching = matching.Where(item => ReminderText.Matches(item, _query));
        var events = matching.ToList();

        List<object> rows;
        if (showCalendar)
        {
            RenderMonth(events, today);
            var day = events.Where(item => item.Start.LocalDateTime.Date == _selectedDate).ToList();
            rows = new List<object>(day.Count + 1);
            if (day.Count > 0) rows.Add(new GroupHeaderRow(LongDate(_selectedDate), day.Count));
            rows.AddRange(day.Select(item => new ReminderRow(item, now, today, archivedView: false, showDate: false, RepeatTextFor(item))));
        }
        else
        {
            rows = new List<object>(events.Count + 8);
            foreach (var entry in ReminderText.BuildEntries(events, today, archived))
            {
                if (entry is GroupEntry group) rows.Add(new GroupHeaderRow(group.Group.Label, group.Count));
                else if (entry is EventEntry single) rows.Add(new ReminderRow(single.Event, now, today, archived, showDate: true, RepeatTextFor(single.Event)));
            }
        }

        var count = showCalendar ? Math.Max(rows.Count - 1, 0) : events.Count;
        ShowRows(rows, now, today, viewChanged);
        CountText.Text = searching ? $"{count} found" : Counted(count);
        ShowEmptyState(count, searching, archived, showCalendar);
    }

    private void ShowRows(List<object> rows, DateTimeOffset now, DateOnly today, bool viewChanged)
    {
        if (!viewChanged && SameRows(_rows, rows))
        {
            foreach (var row in _rows.OfType<ReminderRow>()) row.Refresh(now, today);
            return;
        }
        var offset = viewChanged ? 0 : ListScroll?.VerticalOffset ?? 0;
        var selected = _selectedId is null ? null : rows.OfType<ReminderRow>().FirstOrDefault(row => row.Event.Id == _selectedId);
        _rows = rows;
        _binding = true;
        ReminderList.ItemsSource = rows;
        ReminderList.SelectedItem = selected;
        _binding = false;
        if (selected is null) _selectedId = null;
        var reveal = _revealSelection ? selected : null;
        _revealSelection = false;
        // After the new rows are laid out: back to where the list was, or to the reminder asked for.
        Dispatcher.InvokeAsync(() =>
        {
            ListScroll?.ScrollToVerticalOffset(offset);
            if (reveal is not null) ReminderList.ScrollIntoView(reveal);
        }, DispatcherPriority.Loaded);
    }

    /// <summary>
    /// The minute-by-minute refresh usually finds the same reminders; skipping the rebind then keeps
    /// the scroll position, the open row and any text being selected in it.
    /// </summary>
    private static bool SameRows(List<object> current, List<object> next)
    {
        if (current.Count != next.Count) return false;
        for (var index = 0; index < next.Count; index++)
        {
            var same = (current[index], next[index]) switch
            {
                (GroupHeaderRow shown, GroupHeaderRow wanted) => shown.Label == wanted.Label && shown.CountText == wanted.CountText,
                (ReminderRow shown, ReminderRow wanted) => shown.Shows(wanted),
                _ => false,
            };
            if (!same) return false;
        }
        return true;
    }

    private void RenderMonth(List<CalendarEvent> events, DateOnly today)
    {
        MonthHeading.Text = _visibleMonth.ToString("MMMM yyyy", CultureInfo.InvariantCulture);
        var byDate = events.ToLookup(item => item.Start.LocalDateTime.Date);
        var leadingBlanks = ReminderText.LeadingBlankDays(_visibleMonth);
        var days = DateTime.DaysInMonth(_visibleMonth.Year, _visibleMonth.Month);
        // Always six weeks, so the panel keeps its height from month to month.
        var cells = new List<DayCell>(42);
        var signature = new StringBuilder();
        for (var index = 0; index < 42; index++)
        {
            var number = index - leadingBlanks + 1;
            if (number < 1 || number > days)
            {
                cells.Add(new DayCell());
                continue;
            }
            var date = _visibleMonth.AddDays(number - 1);
            var due = byDate[date].ToList();
            var assessments = due.Select(item => item.Assessment).Where(kind => kind != AssessmentType.None).Distinct().ToList();
            var dots = due.Take(3).Select(item => EventColors.ForEvent(item)).ToList();
            var cell = new DayCell
            {
                Date = date,
                IsToday = DateOnly.FromDateTime(date) == today,
                IsSelected = date == _selectedDate,
                HasAssessment = assessments.Count > 0,
                Dots = dots,
                ToolTip = due.Count == 0 ? null
                    : Counted(due.Count) + (assessments.Count > 0 ? $" - {string.Join(", ", assessments)}" : ""),
            };
            cells.Add(cell);
            signature.Append(CultureInfo.InvariantCulture, $"{date:yyyyMMdd}{cell.IsToday}{cell.IsSelected}{cell.ToolTip}");
            foreach (var dot in dots) signature.Append(dot.Color);
            signature.Append(';');
        }
        var key = signature.ToString();
        if (key == _monthSignature) return;
        _monthSignature = key;
        MonthDays.ItemsSource = cells;
    }

    private void ShowEmptyState(int count, bool searching, bool archived, bool showCalendar)
    {
        _emptyAction = null;
        EmptyLink.Visibility = Visibility.Collapsed;
        EmptyPanel.Visibility = count > 0 ? Visibility.Collapsed : Visibility.Visible;
        if (count > 0) return;
        if (searching)
        {
            EmptyText.Text = $"No reminders match \"{_query}\".";
            OfferWhenEmpty("Clear the search", () => FindBox.Clear());
        }
        else if (!Paired && _events.Count == 0)
        {
            EmptyText.Text = "Your reminders appear here once this PC is paired with the cloud.";
            OfferWhenEmpty("Pair this PC...", () => _ = OpenConnectionAsync());
        }
        else
        {
            EmptyText.Text = archived ? "No archived reminders."
                : showCalendar ? $"Nothing on {LongDate(_selectedDate)}."
                : _section == Deliveries ? "No deliveries on the way."
                : "No upcoming reminders.";
        }
    }

    private void OfferWhenEmpty(string label, Action action)
    {
        _emptyAction = action;
        EmptyLink.Content = label;
        EmptyLink.Visibility = Visibility.Visible;
    }

    private void RefreshCourses()
    {
        var now = Now;
        var choices = new List<CourseChoice> { new(null, "All classes", null) };
        choices.AddRange(_events
            .Where(item => item.IsCanvas && !item.IsArchivedAt(now))
            .GroupBy(item => item.CourseKey)
            .Select(group => new CourseChoice(group.Key, ReminderText.CompactCourseLabel(group.First().CourseLabel), EventColors.ForCourse(group.Key)))
            .OrderBy(choice => choice.Label, StringComparer.OrdinalIgnoreCase));
        if (choices.SequenceEqual(_courses)) return;
        _courses = choices;
        _binding = true;
        CourseFilter.ItemsSource = choices;
        CourseFilter.SelectedItem = choices.FirstOrDefault(choice => choice.Key == _courseKey) ?? choices[0];
        _binding = false;
        _courseKey = (CourseFilter.SelectedItem as CourseChoice)?.Key;
    }

    private static string LongDate(DateTime date) => date.ToString("dddd, MMMM d", CultureInfo.InvariantCulture);

    /// <summary>"1 reminder", "3 reminders"; on the Deliveries tab, "1 delivery", "3 deliveries".</summary>
    private string Counted(int count) => _section == Deliveries
        ? count == 1 ? "1 delivery" : $"{count} deliveries"
        : count == 1 ? "1 reminder" : $"{count} reminders";

    private RadioButton TabFor(string section) => section switch
    {
        School => SchoolTab,
        Deliveries => DeliveriesTab,
        All => AllTab,
        Archived => ArchivedTab,
        Kitchen => KitchenTab,
        _ => LifeTab,
    };

    // ------------------------------------------------------------ changing the view

    internal void ShowSection(string section)
    {
        _section = section;
        _view.Section = section;
        _store.SaveView(_view);
        _selectedId = null;
        Render(viewChanged: true);
        // The kitchen is fetched each time its tab is shown, and only then kept fresh.
        if (section == Kitchen) _ = FetchKitchenAsync();
        else ScheduleKitchenRefresh();
    }

    internal void SetCalendarMode(bool calendar)
    {
        _calendarMode = calendar;
        _view.CalendarMode = calendar;
        _store.SaveView(_view);
        _selectedId = null;
        Render(viewChanged: true);
    }

    private void ShowMonth(DateTime month)
    {
        var today = Now.LocalDateTime.Date;
        _visibleMonth = new DateTime(month.Year, month.Month, 1);
        _selectedDate = month.Year == today.Year && month.Month == today.Month ? today : _visibleMonth;
        _selectedId = null;
        Render(viewChanged: true);
    }

    /// <summary>Opens the list on one reminder, whatever tab, class or search was showing. Reminder balloons lead here.</summary>
    internal void OpenReminder(string id)
    {
        if (_background) ShowFromTray();
        var item = _events.FirstOrDefault(existing => existing.Id == id);
        if (item is null) return;
        _query = "";
        FindBox.Clear();
        if (item.IsArchivedAt(Now)) _section = Archived;
        else if (_section is not (Life or School or Deliveries) || item.ResolvedDomain != _section) _section = All;
        if (_section == School && _courseKey is not null && item.CourseKey != _courseKey)
        {
            _courseKey = null;
            _binding = true;
            CourseFilter.SelectedIndex = 0;
            _binding = false;
        }
        _view.Section = _section;
        _store.SaveView(_view);
        _selectedDate = item.Start.LocalDateTime.Date;
        _visibleMonth = new DateTime(_selectedDate.Year, _selectedDate.Month, 1);
        _selectedId = id;
        _revealSelection = true;
        Render(viewChanged: true);
    }

    private void Section_Click(object sender, RoutedEventArgs e) =>
        ShowSection(sender == SchoolTab ? School : sender == DeliveriesTab ? Deliveries : sender == AllTab ? All
            : sender == ArchivedTab ? Archived : sender == KitchenTab ? Kitchen : Life);

    private void ListMode_Click(object sender, RoutedEventArgs e) => SetCalendarMode(false);

    private void CalendarMode_Click(object sender, RoutedEventArgs e) => SetCalendarMode(true);

    private void PreviousMonth_Click(object sender, RoutedEventArgs e) => ShowMonth(_visibleMonth.AddMonths(-1));

    private void NextMonth_Click(object sender, RoutedEventArgs e) => ShowMonth(_visibleMonth.AddMonths(1));

    private void Today_Click(object sender, RoutedEventArgs e) => ShowMonth(Now.LocalDateTime.Date);

    private void Day_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is not DayCell { Date: { } date }) return;
        _selectedDate = date;
        _selectedId = null;
        Render(viewChanged: true);
    }

    private void CourseFilter_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_binding) return;
        _courseKey = (CourseFilter.SelectedItem as CourseChoice)?.Key;
        _selectedId = null;
        if (IsLoaded) Render(viewChanged: true);
    }

    private void FindBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        FindClear.Visibility = FindBox.Text.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
        var query = FindBox.Text.Trim();
        if (query == _query) return;
        _query = query;
        _selectedId = null;
        if (IsLoaded) Render(viewChanged: true);
    }

    private void FindClear_Click(object sender, RoutedEventArgs e)
    {
        FindBox.Clear();
        FindBox.Focus();
    }

    private void Find_Click(object sender, RoutedEventArgs e) => FocusFind();

    private void FocusFind()
    {
        FindBox.Focus();
        FindBox.SelectAll();
    }

    private void EmptyLink_Click(object sender, RoutedEventArgs e) => _emptyAction?.Invoke();

    protected override void OnPreviewKeyDown(KeyEventArgs e)
    {
        base.OnPreviewKeyDown(e);
        // An open menu handles its own keys.
        if (e.Handled || Keyboard.FocusedElement is MenuItem) return;
        if (e.Key == Key.F5)
        {
            _ = SyncNowAsync();
            e.Handled = true;
        }
        else if (e.Key == Key.F && Keyboard.Modifiers == ModifierKeys.Control)
        {
            FocusFind();
            e.Handled = true;
        }
        else if (e.Key == Key.Tab && (Keyboard.Modifiers & ModifierKeys.Control) != 0)
        {
            // Ctrl+Tab and Ctrl+Shift+Tab go through the tabs, as in any tabbed dialog.
            string[] sections = [Life, School, Deliveries, All, Archived, Kitchen];
            var step = (Keyboard.Modifiers & ModifierKeys.Shift) != 0 ? sections.Length - 1 : 1;
            ShowSection(sections[(Array.IndexOf(sections, _section) + step) % sections.Length]);
            e.Handled = true;
        }
        else if (e.Key == Key.Escape && FindBox.Text.Length > 0 && _section != Kitchen)
        {
            FindBox.Clear();
            e.Handled = true;
        }
        else if (e.Key == Key.Escape && ReminderList.SelectedItem is not null && _section != Kitchen)
        {
            ReminderList.SelectedItem = null;
            e.Handled = true;
        }
    }

    // ------------------------------------------------------------ the list

    private void ReminderList_PreviewMouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.OriginalSource is not DependencyObject source ||
            ItemsControl.ContainerFromElement(ReminderList, source) is not ListBoxItem container) return;
        if (container.Content is GroupHeaderRow)
        {
            // A group caption is not something to select.
            e.Handled = true;
            return;
        }
        // A click on the open reminder's own row closes it again. Clicks on its check box, its
        // buttons or anywhere in its details are left to those.
        if (!container.IsSelected || !VisualTree.IsInsideNamed(source, "Row", container) ||
            VisualTree.Ancestor<ButtonBase>(source, container) is not null) return;
        ReminderList.SelectedItem = null;
        e.Handled = true;
    }

    private void ReminderList_PreviewMouseWheel(object sender, MouseWheelEventArgs e)
    {
        // The notes of an open reminder swallow the wheel even with nothing left to scroll; the
        // list would then stop dead whenever the pointer crosses them.
        if (e.OriginalSource is not DependencyObject source ||
            VisualTree.Ancestor<TextBoxBase>(source, ReminderList) is not { } notes) return;
        var inner = VisualTree.Descendant<ScrollViewer>(notes);
        var canScroll = inner is not null && (e.Delta < 0 ? inner.VerticalOffset < inner.ScrollableHeight : inner.VerticalOffset > 0);
        if (canScroll || ListScroll is not { } list) return;
        e.Handled = true;
        list.RaiseEvent(new MouseWheelEventArgs(e.MouseDevice, e.Timestamp, e.Delta) { RoutedEvent = MouseWheelEvent });
    }

    private void ReminderList_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key is not (Key.Up or Key.Down)) return;
        if (e.OriginalSource is DependencyObject source && VisualTree.Ancestor<TextBoxBase>(source, ReminderList) is not null) return;
        // Up and Down step from reminder to reminder, over the group captions.
        var step = e.Key == Key.Down ? 1 : -1;
        var index = ReminderList.SelectedItem is { } selected ? _rows.IndexOf(selected) : -1;
        var next = index >= 0 ? index + step : step > 0 ? 0 : _rows.Count - 1;
        while (next >= 0 && next < _rows.Count && _rows[next] is not ReminderRow) next += step;
        e.Handled = true;
        if (next < 0 || next >= _rows.Count) return;
        var row = _rows[next];
        ReminderList.SelectedItem = row;
        Dispatcher.InvokeAsync(() => (ReminderList.ItemContainerGenerator.ContainerFromItem(row) as ListBoxItem)?.Focus(), DispatcherPriority.Input);
    }

    private void ReminderList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_binding) return;
        if (ReminderList.SelectedItem is GroupHeaderRow caption)
        {
            // Reached with Home or Page Up: go on to the first reminder under the caption.
            var index = _rows.IndexOf(caption);
            ReminderList.SelectedItem = index >= 0 && index + 1 < _rows.Count ? _rows[index + 1] : null;
            return;
        }
        var row = ReminderList.SelectedItem as ReminderRow;
        _selectedId = row?.Event.Id;
        // Once the details have opened, bring all of them into view.
        if (row is not null) Dispatcher.InvokeAsync(() => ReminderList.ScrollIntoView(row), DispatcherPriority.Loaded);
    }

    private async void RowDone_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is ReminderRow row) await ChangeDoneAsync(row.Event.Id, !row.Event.Done);
    }

    private async void RowDelete_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is ReminderRow row) await RequestDeleteAsync(row.Event);
    }

    /// <summary>Delete with the right question first: the usual confirmation, or for a repeating reminder which occurrences.</summary>
    internal async Task RequestDeleteAsync(CalendarEvent item)
    {
        if (item.IsOccurrence)
        {
            // The choice is also the confirmation: its default button says Delete.
            switch (AskSeriesScope(item))
            {
                case SeriesScope.ThisOne: await DeleteAsync(item.Id); break;
                case SeriesScope.AllFuture: await DeleteSeriesAsync(item); break;
            }
            return;
        }
        if (!XpMessageBox.Confirm(this, "Delete Reminder", $"Permanently delete \"{item.Title}\" from every synced device?", "Delete")) return;
        await DeleteAsync(item.Id);
    }

    /// <summary>
    /// Asks whether a delete is for this occurrence or this and all future ones; null is Cancel.
    /// Tests replace it, because they never show a window.
    /// </summary>
    internal Func<CalendarEvent, SeriesScope?> AskSeriesScope { get; set; }

    private SeriesScope? AskSeriesScopeWithDialog(CalendarEvent item) =>
        XpSeriesScopeBox.Ask(this, "Delete Repeating Reminder", $"\"{item.Title}\" is a repeating reminder.", "Delete");

    private string? RepeatTextFor(CalendarEvent item) =>
        item.SeriesId is { } id && _series.TryGetValue(id, out var series) ? RepeatText.Describe(series.Rule) : null;

    private static Dictionary<string, ReminderSeries> ToMap(IEnumerable<ReminderSeries> series) =>
        series.GroupBy(item => item.Id, StringComparer.Ordinal).ToDictionary(group => group.Key, group => group.Last(), StringComparer.Ordinal);

    private void RowOpen_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is not ReminderRow { CanOpenLink: true, Event.SourceUrl: { } link }) return;
        try
        {
            Process.Start(new ProcessStartInfo(link) { UseShellExecute = true });
        }
        catch (Exception exception) when (exception is Win32Exception or InvalidOperationException)
        {
            ShowNotice($"Could not open the link: {Friendly(exception)}");
        }
    }

    // ------------------------------------------------------------ changes

    /// <summary>Marks a reminder done or not done on every device, then offers to take that back.</summary>
    internal async Task ChangeDoneAsync(string id, bool done, bool offerUndo = true)
    {
        try
        {
            var item = _events.FirstOrDefault(existing => existing.Id == id);
            if (item is null || item.Done == done || !_busy.Add(id)) return;
            try
            {
                SetStatus("Updating...");
                await _client.SetDoneAsync(item, done, _lifetime.Token);
                item.Done = done;
                ShowActionBar($"{(done ? "Marked done" : "Marked not done")}: {item.Title}",
                    offerUndo ? () => ChangeDoneAsync(id, !done, offerUndo: false) : null);
                Render();
                SetStatus("Updated");
                await SyncAsync(showActivity: true);
            }
            catch (OperationCanceledException) when (_lifetime.IsCancellationRequested)
            {
            }
            catch (Exception exception)
            {
                ShowActionError("Could not update the reminder", exception);
            }
            finally
            {
                _busy.Remove(id);
            }
        }
        finally
        {
            // A check box that was clicked shows the new state at once; put it back if nothing changed.
            foreach (var row in _rows.OfType<ReminderRow>().Where(shown => shown.Event.Id == id)) row.RefreshDone();
        }
    }

    /// <summary>Deletes a reminder on every device. The caller has asked the user first.</summary>
    internal async Task DeleteAsync(string id)
    {
        var item = _events.FirstOrDefault(existing => existing.Id == id);
        if (item is null || !_busy.Add(id)) return;
        try
        {
            SetStatus("Deleting...");
            await _client.DeleteAsync(id, _lifetime.Token);
            _events.RemoveAll(existing => existing.Id == id);
            ShowActionBar($"Deleted: {item.Title}", undo: null);
            RefreshCourses();
            Render();
            SetStatus("Deleted");
            await SyncAsync(showActivity: true);
        }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested)
        {
        }
        catch (Exception exception)
        {
            ShowActionError("Could not delete the reminder", exception);
        }
        finally
        {
            _busy.Remove(id);
        }
    }

    /// <summary>
    /// Deletes this and all future occurrences of <paramref name="item"/>'s series, from its date on.
    /// The caller has asked the user first.
    /// </summary>
    internal async Task DeleteSeriesAsync(CalendarEvent item)
    {
        if (item.SeriesId is not { } seriesId || !_busy.Add(item.Id)) return;
        var from = string.IsNullOrWhiteSpace(item.OccurrenceDate) ? null : item.OccurrenceDate;
        try
        {
            SetStatus("Deleting...");
            await _client.DeleteSeriesAsync(seriesId, from, _lifetime.Token);
            _events.RemoveAll(existing => existing.SeriesId == seriesId && !existing.Done &&
                (from is null ? existing.Start >= Now : string.CompareOrdinal(existing.OccurrenceDate, from) >= 0));
            ShowActionBar($"Deleted: {item.Title} and the ones after it", undo: null);
            RefreshCourses();
            Render();
            SetStatus("Deleted");
            await SyncAsync(showActivity: true);
        }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested)
        {
        }
        catch (Exception exception)
        {
            ShowActionError("Could not delete the repeating reminder", exception);
        }
        finally
        {
            _busy.Remove(item.Id);
        }
    }

    /// <summary>A change the service rejected (a 400 or 404) gets a dialog; anything else the bar above the list.</summary>
    private void ShowActionError(string heading, Exception exception)
    {
        SetStatus($"{heading}.");
        if (exception is BridgeException { StatusCode: HttpStatusCode.BadRequest or HttpStatusCode.NotFound } rejected)
        {
            XpMessageBox.Show(this, "AI Planner", $"{heading}.\n\n{rejected.Message}", XpMessageIcon.Warning);
            return;
        }
        _dismissedNotice = null;
        ShowNotice($"{heading}: {Friendly(exception)}");
    }

    /// <summary>The bar under the list that confirms a change for a few seconds, with Undo when there is one.</summary>
    internal void ShowActionBar(string text, Func<Task>? undo)
    {
        _undo = undo;
        ActionBarText.Text = text;
        ActionBarLink.Visibility = undo is null ? Visibility.Collapsed : Visibility.Visible;
        ActionBar.Visibility = Visibility.Visible;
        _actionBarTimer.Stop();
        _actionBarTimer.Start();
    }

    private void HideActionBar()
    {
        _actionBarTimer.Stop();
        _undo = null;
        ActionBar.Visibility = Visibility.Collapsed;
    }

    private async void ActionBarLink_Click(object sender, RoutedEventArgs e)
    {
        var undo = _undo;
        HideActionBar();
        if (undo is not null) await undo();
    }

    private void ActionBarClose_Click(object sender, RoutedEventArgs e) => HideActionBar();

    private void IssueBarClose_Click(object sender, RoutedEventArgs e)
    {
        _dismissedNotice = _notice;
        ShowNotice(null);
    }

    // ------------------------------------------------------------ reminders

    /// <summary>Pops up the reminders that have just come due, and remembers them across restarts.</summary>
    private void CheckReminders()
    {
        if (!_background || _lifetime.IsCancellationRequested) return;
        var now = Now;
        var due = TakeDueReminders(now);
        if (due.Count == 0) return;
        foreach (var (item, kind) in due) ShowReminder(item, kind);
        if (_settings.FiredNotifications.Count > FiredKeysLimit)
        {
            var live = _events.Where(item => item.Start > now).Select(item => item.Id + ":").ToList();
            _settings.FiredNotifications = _settings.FiredNotifications
                .Where(key => live.Any(prefix => key.StartsWith(prefix, StringComparison.Ordinal))).ToHashSet();
        }
        try
        {
            _store.Save(_settings);
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            // Runs from a timer: a locked settings file must not take the app down.
            _store.Log($"Reminder state save failed: {exception.GetType().Name}");
        }
    }

    /// <summary>
    /// The alerts that have come due within the last two minutes and were not shown before, marked
    /// as shown. When an alert is due is ReminderSchedule's decision, the same as on the phone.
    /// </summary>
    internal List<(CalendarEvent Event, string Kind)> TakeDueReminders(DateTimeOffset now)
    {
        var due = new List<(CalendarEvent, string)>();
        // The grace also lets an "at event time" alert through once the event has just started.
        // A delivery never alerts: it has no alert offsets, whatever lead times are stored on it.
        foreach (var item in _events.Where(item => !item.Done && !item.IsDelivery && item.Start > now.AddMinutes(-ReminderGraceMinutes)))
        {
            foreach (var alarm in ReminderSchedule.Plan(item.Start, item.AlertOffsets))
            {
                if (now < alarm.TriggerAt || now >= alarm.TriggerAt.AddMinutes(ReminderGraceMinutes)) continue;
                if (_settings.FiredNotifications.Add(FiredKey(item, alarm.Kind))) due.Add((item, alarm.Kind));
            }
        }
        return due;
    }

    /// <summary>
    /// Identifies one alert of one event. Keyed on the scheduled time, not the revision: an unrelated
    /// edit must not pop the same reminder again. "N minutes before" alerts keep the key of earlier
    /// versions (the bare number), so an update does not repeat what was already shown.
    /// </summary>
    internal static string FiredKey(CalendarEvent item, string kind) =>
        $"{item.Id}:{item.StartAt}:{(kind.StartsWith("offset_", StringComparison.Ordinal) ? kind["offset_".Length..] : kind)}";

    private void ShowReminder(CalendarEvent item, string kind)
    {
        var id = item.Id;
        var lines = new List<string> { ReminderText.EventDate(item.Start) };
        if (item.IsCanvas) lines.Add($"{ReminderText.CompactCourseLabel(item.CourseLabel)} - {ReminderText.SchoolItemType(item.SchoolItemType)}");
        else if (!string.IsNullOrWhiteSpace(item.Location)) lines.Add(item.Location.Trim());
        // It stays until it is closed, but not past its use: an hour after the event has started.
        var lifetime = item.Start - Now + TimeSpan.FromHours(1);
        XpBalloon.Show(
            ReminderText.AlertTitle(kind, item.Title),
            string.Join("\n", lines),
            item.Assessment == AssessmentType.None ? "Icon.Bell" : "Icon.Warning",
            onClick: () => OpenReminder(id),
            actions: [new BalloonAction("Open", () => OpenReminder(id)), new BalloonAction("Mark done", () => _ = ChangeDoneAsync(id, done: true))],
            lifetime: lifetime > TimeSpan.FromMinutes(1) ? lifetime : TimeSpan.FromMinutes(1),
            sound: true);
    }

    // ------------------------------------------------------------ menu

    private void Exit_Click(object sender, RoutedEventArgs e) => Exit();

    private async void Sync_Click(object sender, RoutedEventArgs e) => await SyncNowAsync();

    private async void Connection_Click(object sender, RoutedEventArgs e) => await OpenConnectionAsync();

    private async void ConnectBar_Click(object sender, MouseButtonEventArgs e) => await OpenConnectionAsync();

    private void Options_Click(object sender, RoutedEventArgs e) => new OptionsWindow(_view, _store) { Owner = this }.ShowDialog();

    private void About_Click(object sender, RoutedEventArgs e)
    {
        var now = Now;
        var upcoming = _events.Count(item => !item.IsArchivedAt(now));
        var facts = new List<DetailLine>
        {
            new("Reminders", $"{upcoming} upcoming, {_events.Count - upcoming} archived"),
            new("Cloud", !Paired ? "Not connected"
                : _lastSync is { } synced ? $"Connected, last sync {ReminderText.EventDate(synced)}"
                : "Connected"),
            new("Alerts", "Pop up while AI Planner is running"),
        };
        new AboutWindow(facts) { Owner = this }.ShowDialog();
    }
}
