using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;

namespace AICalendar.Desktop.Xp;

/// <summary>A link at the bottom of a balloon. Choosing it runs the action and closes the balloon.</summary>
public sealed record BalloonAction(string Label, Action Run);

/// <summary>
/// Balloon tip in the corner of the screen, next to the notification area. Balloons stay until they
/// are closed or their time is up, stack upwards (the oldest at the bottom, with the tail), and
/// never take the focus.
/// </summary>
public partial class XpBalloon : Window
{
    /// <summary>More than this many at once and the oldest is closed.</summary>
    private const int MaxOpen = 4;
    private const double EdgeRight = 4;
    private const double EdgeBottom = 2;

    private static readonly List<XpBalloon> Open = [];

    /// <summary>The area balloons stack in: the main screen without the taskbar. The tests move it off screen.</summary>
    internal static Func<Rect> Area { get; set; } = () => SystemParameters.WorkArea;

    /// <summary>The balloons on screen, oldest first.</summary>
    internal static IReadOnlyList<XpBalloon> Showing => Open;
    private readonly Action? _onClick;
    private readonly DispatcherTimer? _lifetime;

    internal XpBalloon(string title, string message, string iconKey, Action? onClick,
        IReadOnlyList<BalloonAction>? actions = null, TimeSpan? lifetime = null)
    {
        InitializeComponent();
        _onClick = onClick;
        Heading.Text = title;
        Message.Text = message;
        Message.Visibility = message.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
        Picture.Source = (ImageSource)FindResource(iconKey);
        if (onClick is not null) Shape.Cursor = Cursors.Hand;
        foreach (var action in actions ?? [])
        {
            var link = new Button
            {
                Style = (Style)FindResource("Xp.LinkButton"),
                Content = action.Label,
                Margin = new Thickness(0, 0, 14, 0),
                Focusable = false,
            };
            link.Click += (_, _) =>
            {
                Close();
                action.Run();
            };
            Links.Children.Add(link);
            Links.Visibility = Visibility.Visible;
        }
        if (lifetime is { } time)
        {
            _lifetime = new DispatcherTimer { Interval = time };
            _lifetime.Tick += (_, _) => Close();
        }

        // Parked at the corner and transparent until the first layout says how tall it is.
        var area = Area();
        Left = area.Right - Width - EdgeRight;
        Top = area.Bottom - 120;
        SizeChanged += (_, _) => Restack();
        Loaded += (_, _) =>
        {
            Restack();
            BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(160)));
            _lifetime?.Start();
        };
        Closed += (_, _) =>
        {
            _lifetime?.Stop();
            Open.Remove(this);
            Restack();
        };
    }

    /// <summary>
    /// Shows a balloon. <paramref name="onClick"/> runs when its text is clicked; a balloon without
    /// a <paramref name="lifetime"/> stays until it is closed.
    /// </summary>
    public static XpBalloon Show(string title, string message, string iconKey = "Icon.Info", Action? onClick = null,
        IReadOnlyList<BalloonAction>? actions = null, TimeSpan? lifetime = null, bool sound = false)
    {
        var balloon = new XpBalloon(title, message, iconKey, onClick, actions, lifetime);
        while (Open.Count >= MaxOpen) Open[0].Close();
        Open.Add(balloon);
        balloon.Show();
        if (sound) NativeMethods.PlayReminderSound();
        return balloon;
    }

    public static void CloseAll()
    {
        foreach (var balloon in Open.ToList()) balloon.Close();
    }

    /// <summary>Stacks the open balloons from the bottom of the work area upwards. Only the lowest has the tail.</summary>
    private static void Restack()
    {
        var area = Area();
        var bottom = area.Bottom - EdgeBottom;
        for (var index = 0; index < Open.Count; index++)
        {
            var balloon = Open[index];
            balloon.Tail.Visibility = index == 0 ? Visibility.Visible : Visibility.Collapsed;
            if (!balloon.IsLoaded) continue;
            balloon.Left = area.Right - balloon.Width - EdgeRight;
            balloon.Top = bottom - balloon.ActualHeight;
            bottom = balloon.Top;
        }
    }

    protected override void OnSourceInitialized(EventArgs e)
    {
        base.OnSourceInitialized(e);
        NativeMethods.MakeToolWindowWithoutActivation(new WindowInteropHelper(this).Handle);
    }

    private void Body_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
    {
        if (_onClick is null) return;
        Close();
        _onClick();
    }

    private void Close_Click(object sender, RoutedEventArgs e) => Close();
}
