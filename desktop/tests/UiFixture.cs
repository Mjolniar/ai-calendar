using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using AICalendar.Desktop.Xp;

namespace AICalendar.Desktop.Tests;

[CollectionDefinition(Name)]
public sealed class UiCollection : ICollectionFixture<UiFixture>
{
    public const string Name = "UI";
}

/// <summary>
/// One thread that plays the app's UI thread for all window tests: it installs the theme once and
/// then runs whatever the tests hand it. Windows are shown far off screen and never take the focus,
/// so the tests can run while the PC is in use.
///
/// The application here is a bare one with the theme installed, never the app's own App class:
/// an Application queues its OnStartup from the constructor, so creating an App would open the
/// real main window, with its tray icon, the saved pairing and a sync.
/// </summary>
public sealed class UiFixture : IDisposable
{

    private readonly Thread _thread;
    private readonly Dispatcher _dispatcher;

    public UiFixture()
    {
        Artifacts = Path.Combine(ProjectFolder(), "artifacts");
        Directory.CreateDirectory(Artifacts);
        Dispatcher? dispatcher = null;
        Exception? failure = null;
        using var ready = new ManualResetEventSlim();
        _thread = new Thread(() =>
        {
            try
            {
                dispatcher = Dispatcher.CurrentDispatcher;
                XpWindow.AlwaysShowActive = true;
                // Windows come and go here; closing the first one must not end the application.
                XpTheme.Install(new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown });
            }
            catch (Exception exception)
            {
                failure = exception;
            }
            ready.Set();
            if (failure is null) Dispatcher.Run();
        })
        { IsBackground = true, Name = "UI tests" };
        _thread.SetApartmentState(ApartmentState.STA);
        _thread.Start();
        ready.Wait();
        if (failure is not null) throw new InvalidOperationException("The theme did not load.", failure);
        _dispatcher = dispatcher!;
    }

    /// <summary>Where the render tests leave their pictures: tests/artifacts.</summary>
    public string Artifacts { get; }

    public void Run(Action work) => _dispatcher.Invoke(work);

    public void Dispose()
    {
        _dispatcher.InvokeShutdown();
        _thread.Join(TimeSpan.FromSeconds(10));
        try { Directory.Delete(Sample.TempRoot, recursive: true); }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException) { }
    }

    /// <summary>Lets layout, data binding and everything queued behind them finish.</summary>
    public static void Settle()
    {
        for (var pass = 0; pass < 3; pass++) Dispatcher.CurrentDispatcher.Invoke(() => { }, DispatcherPriority.ApplicationIdle);
    }

    /// <summary>Shows a window where nobody sees it, without activating it.</summary>
    public static T Show<T>(T window) where T : Window
    {
        window.WindowStartupLocation = WindowStartupLocation.Manual;
        window.Left = -30000;
        window.Top = -30000;
        window.ShowActivated = false;
        window.ShowInTaskbar = false;
        window.Show();
        Settle();
        return window;
    }

    /// <summary>
    /// The main window over a store, at a fixed moment unless a clock is given. The calendar service
    /// is always a stand-in: no test reaches the network.
    /// </summary>
    internal static MainWindow Open(SettingsStore store, Func<DateTimeOffset>? clock = null, FakeBridge? bridge = null)
    {
        bridge ??= new FakeBridge(store.LoadEvents());
        return Show(new MainWindow(store, background: false, clock ?? (() => Sample.Now), _ => bridge));
    }

    /// <summary>
    /// Saves a picture of an element as it is laid out now. A backdrop shows what is transparent.
    /// With <paramref name="settle"/> false nothing queued is run first; an open popup then never
    /// reaches the screen.
    /// </summary>
    public void Save(FrameworkElement element, string name, Brush? backdrop = null, bool settle = true)
    {
        if (settle) Settle();
        var width = Math.Max(1, (int)Math.Ceiling(element.ActualWidth));
        var height = Math.Max(1, (int)Math.Ceiling(element.ActualHeight));
        var picture = new DrawingVisual();
        using (var context = picture.RenderOpen())
        {
            var area = new Rect(0, 0, width, height);
            if (backdrop is not null) context.DrawRectangle(backdrop, null, area);
            // Through a brush, so the element is drawn from its own corner wherever its parent put it.
            context.DrawRectangle(new VisualBrush(element) { Stretch = Stretch.None, AlignmentX = AlignmentX.Left, AlignmentY = AlignmentY.Top, ViewboxUnits = BrushMappingMode.Absolute, Viewbox = area }, null, area);
        }
        var bitmap = new RenderTargetBitmap(width, height, 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(picture);
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var stream = File.Create(Path.Combine(Artifacts, name + ".png"));
        encoder.Save(stream);
    }

    private static string ProjectFolder()
    {
        for (var folder = new DirectoryInfo(AppContext.BaseDirectory); folder is not null; folder = folder.Parent)
        {
            if (File.Exists(Path.Combine(folder.FullName, "AI.Calendar.Desktop.Tests.csproj"))) return folder.FullName;
        }
        return AppContext.BaseDirectory;
    }
}
