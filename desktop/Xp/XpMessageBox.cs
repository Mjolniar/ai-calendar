using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace AICalendar.Desktop.Xp;

public enum XpMessageIcon { Info, Warning, Error }

/// <summary>Classic message box in the XP look: icon on the left, text on the right, buttons below.</summary>
public sealed class XpMessageBox : XpWindow
{
    private bool _accepted;

    internal XpMessageBox(string title, string message, XpMessageIcon icon, string accept, string? cancel, bool cancelIsDefault)
    {
        Title = title;
        ResizeMode = ResizeMode.NoResize;
        SizeToContent = SizeToContent.WidthAndHeight;
        ShowInTaskbar = false;
        WindowStartupLocation = WindowStartupLocation.CenterOwner;

        var picture = new Image
        {
            Source = (ImageSource)FindResource(icon switch
            {
                XpMessageIcon.Warning => "Icon.Warning",
                XpMessageIcon.Error => "Icon.Error",
                _ => "Icon.Info",
            }),
            Width = 32,
            Height = 32,
            Margin = new Thickness(0, 0, 14, 0),
            VerticalAlignment = VerticalAlignment.Top,
        };
        var text = new TextBlock
        {
            Text = message,
            TextWrapping = TextWrapping.Wrap,
            MaxWidth = 380,
            MinWidth = 180,
            VerticalAlignment = VerticalAlignment.Center,
        };
        var body = new DockPanel { Margin = new Thickness(14, 14, 16, 12) };
        DockPanel.SetDock(picture, Dock.Left);
        body.Children.Add(picture);
        body.Children.Add(text);

        var buttons = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(14, 0, 14, 12),
        };
        var acceptButton = new Button { Content = accept, IsDefault = !cancelIsDefault || cancel is null };
        acceptButton.Click += (_, _) =>
        {
            _accepted = true;
            Close();
        };
        buttons.Children.Add(acceptButton);
        if (cancel is not null)
        {
            var cancelButton = new Button { Content = cancel, IsCancel = true, IsDefault = cancelIsDefault, Margin = new Thickness(8, 0, 0, 0) };
            buttons.Children.Add(cancelButton);
            Loaded += (_, _) => (cancelIsDefault ? cancelButton : acceptButton).Focus();
        }
        else
        {
            acceptButton.IsCancel = true;
            Loaded += (_, _) => acceptButton.Focus();
        }

        var layout = new StackPanel();
        layout.Children.Add(body);
        layout.Children.Add(buttons);
        Content = layout;
    }

    /// <summary>Shows a notice with a single OK button.</summary>
    public static void Show(Window? owner, string title, string message, XpMessageIcon icon = XpMessageIcon.Info) =>
        Open(owner, new XpMessageBox(title, message, icon, "OK", null, false));

    /// <summary>
    /// Asks before something that cannot be undone. Returns true only when the accept button is
    /// chosen; Cancel is the default button, so Enter and Esc both back out.
    /// </summary>
    public static bool Confirm(Window? owner, string title, string message, string accept, XpMessageIcon icon = XpMessageIcon.Warning) =>
        Open(owner, new XpMessageBox(title, message, icon, accept, "Cancel", true));

    private static bool Open(Window? owner, XpMessageBox box)
    {
        if (owner is { IsVisible: true }) box.Owner = owner;
        else box.WindowStartupLocation = WindowStartupLocation.CenterScreen;
        box.ShowDialog();
        return box._accepted;
    }
}
