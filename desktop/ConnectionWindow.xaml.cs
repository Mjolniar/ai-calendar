using System.IO;
using System.Net;
using System.Windows;
using System.Windows.Media;
using AICalendar.Desktop.Xp;

namespace AICalendar.Desktop;

/// <summary>What the main window knows about the cloud link when the dialog opens.</summary>
public sealed record ConnectionState(bool Paired, bool SavedButUnreadable, DateTimeOffset? LastSync, string? Problem);

/// <summary>
/// Tools > Cloud Connection: shows whether this PC is paired and pairs it (again). The dialog
/// result is true when a new pairing was saved.
/// </summary>
public partial class ConnectionWindow : XpWindow
{
    private readonly DesktopSettings _settings;
    private readonly SettingsStore _store;
    private readonly CancellationTokenSource _closing = new();

    public ConnectionWindow(DesktopSettings settings, SettingsStore store, ConnectionState state)
    {
        InitializeComponent();
        _settings = settings;
        _store = store;
        ServerUrlBox.Text = settings.ServerUrl;
        ShowState(state);
        Loaded += (_, _) => PairingCodeBox.Focus();
        Closed += (_, _) => _closing.Cancel();
    }

    private void ShowState(ConnectionState state)
    {
        var (icon, title, detail) = state switch
        {
            { Paired: true, Problem: { } problem } => ("Icon.CloudWarn", "This PC is paired, but the last sync failed.", problem),
            { Paired: true, LastSync: { } synced } => ("Icon.CloudOk", "This PC is connected.", $"Last synced {ReminderText.EventDate(synced)}."),
            { Paired: true } => ("Icon.CloudOk", "This PC is connected.", "Not synced yet."),
            { SavedButUnreadable: true } => ("Icon.CloudOff", "The saved connection could not be read.", "Pair this PC again to replace it."),
            _ => ("Icon.CloudOff", "This PC is not connected.", "Enter the bridge address and the pairing code to show your reminders here."),
        };
        StateIcon.Source = (ImageSource)FindResource(icon);
        StateTitle.Text = title;
        StateDetail.Text = detail;
        PairGroup.Header = state.Paired ? "Pair again" : "Pair this PC";
    }

    private async void Connect_Click(object sender, RoutedEventArgs e)
    {
        var address = ServerUrlBox.Text.Trim().TrimEnd('/');
        var code = PairingCodeBox.Password;
        if (!IsBridgeAddress(address))
        {
            ShowStatus("Enter the bridge address. It starts with https://", error: true);
            ServerUrlBox.Focus();
            return;
        }
        if (string.IsNullOrWhiteSpace(code))
        {
            ShowStatus("Enter the pairing code.", error: true);
            PairingCodeBox.Focus();
            return;
        }
        ConnectButton.IsEnabled = false;
        ShowStatus("Connecting...", error: false);
        try
        {
            // Paired against a copy: a failed attempt must not disturb the connection in use.
            var candidate = new DesktopSettings { ServerUrl = address, ClientId = _settings.ClientId };
            var token = await new BridgeClient(candidate).PairAsync(code, _closing.Token);
            _settings.ServerUrl = address;
            _settings.DeviceToken = token;
            _store.Save(_settings);
            PairingCodeBox.Clear();
            DialogResult = true;
        }
        catch (OperationCanceledException) when (_closing.IsCancellationRequested) { }
        catch (Exception exception)
        {
            _store.Log($"Pairing failed: {exception.GetType().Name}");
            ShowStatus(Describe(exception), error: true);
            ConnectButton.IsEnabled = true;
        }
    }

    private static string Describe(Exception exception) => exception switch
    {
        BridgeException { StatusCode: HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden } => "The pairing code was not accepted.",
        BridgeException { StatusCode: HttpStatusCode.NotFound } => "No calendar service answered at this address.",
        IOException or UnauthorizedAccessException => $"Paired, but the connection could not be saved: {exception.Message}",
        _ => exception.Message,
    };

    /// <summary>The pairing code travels to this address, so only https is accepted (http for this PC itself).</summary>
    private static bool IsBridgeAddress(string address) =>
        Uri.TryCreate(address, UriKind.Absolute, out var uri) &&
        (uri.Scheme == Uri.UriSchemeHttps || (uri.Scheme == Uri.UriSchemeHttp && uri.IsLoopback));

    private void ShowStatus(string text, bool error)
    {
        StatusText.Text = text;
        StatusText.SetResourceReference(System.Windows.Controls.TextBlock.ForegroundProperty, error ? "Xp.ErrorText" : "Xp.TextDim");
        StatusText.Visibility = Visibility.Visible;
    }
}
