using System.Threading;
using System.Windows;
using System.Windows.Threading;
using AICalendar.Desktop.Xp;

namespace AICalendar.Desktop;

public partial class App : System.Windows.Application
{
    private const string InstanceMutexName = @"Local\AICalendarDesktop.SingleInstance";
    private const string ActivateEventName = @"Local\AICalendarDesktop.Activate";
    private static SettingsStore? _store;
    private Mutex? _instanceMutex;
    private EventWaitHandle? _activateSignal;
    private RegisteredWaitHandle? _activateWait;
    private int _errorDialogOpen;

    /// <summary>Single settings store shared by the window and the crash logger.</summary>
    internal static SettingsStore Store => _store ??= new SettingsStore();

    protected override void OnStartup(StartupEventArgs e)
    {
        DispatcherUnhandledException += OnDispatcherUnhandledException;
        AppDomain.CurrentDomain.UnhandledException += (_, args) => LogCrash("AppDomain", args.ExceptionObject as Exception);
        TaskScheduler.UnobservedTaskException += (_, args) =>
        {
            LogCrash("UnobservedTask", args.Exception);
            args.SetObserved();
        };

        // One instance per Windows session: a second launch (shortcut double-click) just
        // brings the running window forward instead of duplicating reminder popups and
        // racing on settings.json writes.
        _instanceMutex = new Mutex(true, InstanceMutexName, out var createdNew);
        if (!createdNew)
        {
            try
            {
                if (EventWaitHandle.TryOpenExisting(ActivateEventName, out var signal))
                    using (signal) signal.Set();
            }
            catch (Exception exception) when (exception is UnauthorizedAccessException or WaitHandleCannotBeOpenedException) { }
            _instanceMutex.Dispose();
            _instanceMutex = null;
            Shutdown(0);
            return;
        }
        _activateSignal = new EventWaitHandle(false, EventResetMode.AutoReset, ActivateEventName);
        _activateWait = ThreadPool.RegisterWaitForSingleObject(
            _activateSignal, (_, _) => Dispatcher.BeginInvoke(ActivateMainWindow), null, Timeout.Infinite, false);

        base.OnStartup(e);
        MainWindow window;
        try
        {
            XpTheme.Install(this, Store.LoadView().DarkMode);
            window = new MainWindow();
        }
        catch (Exception exception)
        {
            // Without a window the process would linger unseen and hold the single-instance lock.
            LogCrash("Startup", exception);
            System.Windows.MessageBox.Show(
                $"AI Planner could not start.\n\n{exception.GetType().Name}: {Truncate(exception.Message, 300)}",
                "AI Planner", MessageBoxButton.OK, MessageBoxImage.Error);
            Shutdown(1);
            return;
        }
        MainWindow = window;
        window.Show();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _activateWait?.Unregister(null);
        _activateSignal?.Dispose();
        if (_instanceMutex is not null)
        {
            try { _instanceMutex.ReleaseMutex(); } catch (ApplicationException) { }
            _instanceMutex.Dispose();
        }
        base.OnExit(e);
    }

    private void ActivateMainWindow()
    {
        if (MainWindow is not { } window) return;
        if (window.WindowState == WindowState.Minimized) window.WindowState = WindowState.Normal;
        window.Show();
        window.Activate();
    }

    private void OnDispatcherUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        LogCrash("Dispatcher", e.Exception);
        // Keep the reminder app alive; one faulty handler must not take it down.
        e.Handled = true;
        if (Interlocked.Exchange(ref _errorDialogOpen, 1) == 1) return;
        var message = $"Something went wrong, but AI Planner is still running.\n\n{e.Exception.GetType().Name}: {Truncate(e.Exception.Message, 300)}";
        try
        {
            try { XpMessageBox.Show(MainWindow, "AI Planner", message, XpMessageIcon.Warning); }
            catch (Exception)
            {
                // The themed box itself failed (a broken resource, say): fall back to the system one.
                System.Windows.MessageBox.Show(message, "AI Planner", MessageBoxButton.OK, MessageBoxImage.Warning);
            }
        }
        catch (Exception) { }
        finally { Interlocked.Exchange(ref _errorDialogOpen, 0); }
    }

    private static void LogCrash(string source, Exception? exception)
    {
        try
        {
            // Type, message and stack only; request headers (the device token) are never part of these.
            Store.Log($"Unhandled {source}: {exception?.GetType().FullName}: {Truncate(exception?.Message ?? "", 300)}{Environment.NewLine}{exception?.StackTrace}");
        }
        catch (Exception) { }
    }

    private static string Truncate(string value, int length) => value.Length > length ? value[..length] + "..." : value;
}
