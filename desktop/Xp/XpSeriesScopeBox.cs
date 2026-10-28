using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace AICalendar.Desktop.Xp;

/// <summary>Which occurrences of a repeating reminder a change applies to.</summary>
public enum SeriesScope { ThisOne, AllFuture }

/// <summary>
/// Asks whether a change to an occurrence of a repeating reminder is for this one or for this and all
/// future ones. Laid out like <see cref="XpMessageBox"/>: icon, text and the two choices, buttons below.
/// </summary>
public sealed class XpSeriesScopeBox : XpWindow
{
    private SeriesScope? _choice;

    internal XpSeriesScopeBox(string title, string message, string accept)
    {
        Title = title;
        ResizeMode = ResizeMode.NoResize;
        SizeToContent = SizeToContent.WidthAndHeight;
        ShowInTaskbar = false;
        WindowStartupLocation = WindowStartupLocation.CenterOwner;

        var picture = new Image
        {
            Source = (ImageSource)FindResource("Icon.Repeat"),
            Width = 32,
            Height = 32,
            Margin = new Thickness(0, 0, 14, 0),
            VerticalAlignment = VerticalAlignment.Top,
        };
        ThisOne = new RadioButton { Content = "_This one", IsChecked = true, Margin = new Thickness(0, 10, 0, 0) };
        AllFuture = new RadioButton { Content = "This and _all future ones", Margin = new Thickness(0, 8, 0, 0) };
        var choices = new StackPanel { MinWidth = 220, MaxWidth = 380 };
        choices.Children.Add(new TextBlock { Text = message, TextWrapping = TextWrapping.Wrap });
        choices.Children.Add(ThisOne);
        choices.Children.Add(AllFuture);
        var body = new DockPanel { Margin = new Thickness(14, 14, 16, 12) };
        DockPanel.SetDock(picture, Dock.Left);
        body.Children.Add(picture);
        body.Children.Add(choices);

        var acceptButton = new Button { Content = accept, IsDefault = true };
        acceptButton.Click += (_, _) =>
        {
            _choice = AllFuture.IsChecked == true ? SeriesScope.AllFuture : SeriesScope.ThisOne;
            Close();
        };
        var cancelButton = new Button { Content = "Cancel", IsCancel = true, Margin = new Thickness(8, 0, 0, 0) };
        var buttons = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(14, 0, 14, 12),
        };
        buttons.Children.Add(acceptButton);
        buttons.Children.Add(cancelButton);
        Loaded += (_, _) => ThisOne.Focus();

        var layout = new StackPanel();
        layout.Children.Add(body);
        layout.Children.Add(buttons);
        Content = layout;
    }

    internal RadioButton ThisOne { get; }
    internal RadioButton AllFuture { get; }

    /// <summary>Shows the choice; null when it was cancelled.</summary>
    public static SeriesScope? Ask(Window? owner, string title, string message, string accept)
    {
        var box = new XpSeriesScopeBox(title, message, accept);
        if (owner is { IsVisible: true }) box.Owner = owner;
        else box.WindowStartupLocation = WindowStartupLocation.CenterScreen;
        box.ShowDialog();
        return box._choice;
    }
}
