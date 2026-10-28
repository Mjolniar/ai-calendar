using System.Windows;
using AICalendar.Desktop.Xp;

namespace AICalendar.Desktop;

/// <summary>Tools > Options. Each choice applies and is saved the moment it is made.</summary>
public partial class OptionsWindow : XpWindow
{
    private readonly ViewSettings _view;
    private readonly SettingsStore _store;

    public OptionsWindow(ViewSettings view, SettingsStore store)
    {
        InitializeComponent();
        _view = view;
        _store = store;
        DarkModeBox.IsChecked = view.DarkMode;
        KeepInTrayBox.IsChecked = view.KeepInTray;
    }

    private void DarkMode_Click(object sender, RoutedEventArgs e)
    {
        _view.DarkMode = DarkModeBox.IsChecked == true;
        XpTheme.Apply(_view.DarkMode);
        _store.SaveView(_view);
    }

    private void KeepInTray_Click(object sender, RoutedEventArgs e)
    {
        _view.KeepInTray = KeepInTrayBox.IsChecked == true;
        _store.SaveView(_view);
    }
}
