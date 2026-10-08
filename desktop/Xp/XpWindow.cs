using System.Windows;
using System.Windows.Input;
using System.Windows.Shell;

namespace AICalendar.Desktop.Xp;

/// <summary>
/// A window whose caption, frame and caption buttons are drawn by the XP theme (style "Xp.WindowStyle").
/// Windows still handles moving, resizing, snapping and maximizing: only the painting is replaced.
/// </summary>
public class XpWindow : Window
{
    /// <summary>
    /// Draws every caption as active. The render tests set it, because their windows are shown
    /// without taking the keyboard focus from whoever is using the PC.
    /// </summary>
    public static bool AlwaysShowActive { get; set; }

    public static readonly DependencyProperty CaptionActiveProperty = DependencyProperty.Register(
        nameof(CaptionActive), typeof(bool), typeof(XpWindow), new PropertyMetadata(true));

    /// <summary>True while the caption should look active; the window template dims it otherwise.</summary>
    public bool CaptionActive
    {
        get => (bool)GetValue(CaptionActiveProperty);
        private set => SetValue(CaptionActiveProperty, value);
    }

    public XpWindow()
    {
        SetResourceReference(StyleProperty, "Xp.WindowStyle");
        WindowChrome.SetWindowChrome(this, new WindowChrome
        {
            CaptionHeight = 29,
            ResizeBorderThickness = new Thickness(6),
            // One pixel of system frame keeps the drop shadow (and the rounded corners on Windows 11).
            GlassFrameThickness = new Thickness(1),
            CornerRadius = new CornerRadius(0),
            UseAeroCaptionButtons = false,
        });
        CommandBindings.Add(new CommandBinding(SystemCommands.CloseWindowCommand, (_, _) => SystemCommands.CloseWindow(this)));
        CommandBindings.Add(new CommandBinding(SystemCommands.MinimizeWindowCommand, (_, _) => SystemCommands.MinimizeWindow(this)));
        CommandBindings.Add(new CommandBinding(SystemCommands.MaximizeWindowCommand, (_, _) => SystemCommands.MaximizeWindow(this)));
        CommandBindings.Add(new CommandBinding(SystemCommands.RestoreWindowCommand, (_, _) => SystemCommands.RestoreWindow(this)));
        Activated += (_, _) => CaptionActive = true;
        Deactivated += (_, _) => CaptionActive = AlwaysShowActive;
    }

    protected override void OnSourceInitialized(EventArgs e)
    {
        base.OnSourceInitialized(e);
        // A window that cannot be resized should not show resize cursors along its edges either.
        if (ResizeMode is (ResizeMode.NoResize or ResizeMode.CanMinimize) && WindowChrome.GetWindowChrome(this) is { } chrome)
            chrome.ResizeBorderThickness = new Thickness(0);
        // A custom-chrome window that sizes to its content measures once with the system frame still
        // counted; measuring again gives it the right size.
        if (SizeToContent != SizeToContent.Manual) InvalidateMeasure();
    }
}
