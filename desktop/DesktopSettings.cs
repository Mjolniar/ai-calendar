using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.IO;

namespace AICalendar.Desktop;

public sealed class DesktopSettings
{
    public string ServerUrl { get; set; } = "";
    public string ClientId { get; set; } = $"windows-{Guid.NewGuid():N}";
    public string ProtectedDeviceToken { get; set; } = "";
    public HashSet<string> FiredNotifications { get; set; } = [];

    private string? _decryptedSource;
    private string? _decryptedToken;

    /// <summary>DPAPI (CurrentUser) protected at rest; decrypted value cached in memory per protected blob.</summary>
    [JsonIgnore]
    public string? DeviceToken
    {
        get
        {
            if (string.IsNullOrWhiteSpace(ProtectedDeviceToken)) return null;
            if (ReferenceEquals(_decryptedSource, ProtectedDeviceToken)) return _decryptedToken;
            string? token;
            try
            {
                var encrypted = Convert.FromBase64String(ProtectedDeviceToken);
                token = Encoding.UTF8.GetString(ProtectedData.Unprotect(encrypted, null, DataProtectionScope.CurrentUser));
            }
            catch { token = null; }
            _decryptedSource = ProtectedDeviceToken;
            _decryptedToken = token;
            return token;
        }
        set
        {
            ProtectedDeviceToken = string.IsNullOrWhiteSpace(value) ? "" : Convert.ToBase64String(
                ProtectedData.Protect(Encoding.UTF8.GetBytes(value), null, DataProtectionScope.CurrentUser));
        }
    }

    /// <summary>Replaces nulls read from a hand-edited or older settings file.</summary>
    internal DesktopSettings Normalize()
    {
        if (string.IsNullOrWhiteSpace(ServerUrl)) ServerUrl = "";
        if (string.IsNullOrWhiteSpace(ClientId)) ClientId = $"windows-{Guid.NewGuid():N}";
        ProtectedDeviceToken ??= "";
        FiredNotifications ??= [];
        FiredNotifications.Remove(null!);
        return this;
    }
}

/// <summary>
/// How the window was last arranged. Kept in its own file (view.json), apart from the pairing in
/// settings.json, so that saving a tab choice can never overwrite the device token.
/// </summary>
public sealed class ViewSettings
{
    public bool DarkMode { get; set; }
    public string Section { get; set; } = "life";
    public bool CalendarMode { get; set; }

    /// <summary>The part of the Kitchen tab last chosen: week, list or pantry.</summary>
    public string KitchenPart { get; set; } = "week";

    /// <summary>Closing the window hides it; the app keeps running in the notification area.</summary>
    public bool KeepInTray { get; set; }

    // Where the window was, in its normal (not maximized) state. Null until it has been closed once.
    public double? WindowLeft { get; set; }
    public double? WindowTop { get; set; }
    public double? WindowWidth { get; set; }
    public double? WindowHeight { get; set; }
    public bool WindowMaximized { get; set; }

    internal ViewSettings Normalize()
    {
        if (Section is not ("life" or "school" or "delivery" or "all" or "archived" or "kitchen")) Section = "life";
        if (KitchenPart is not ("week" or "list" or "pantry")) KitchenPart = "week";
        return this;
    }
}

public sealed class SettingsStore
{
    public bool HasSavedSettings => File.Exists(PathName) || File.Exists(BackupPath);

    public void Log(string message)
    {
        try
        {
            Directory.CreateDirectory(Folder);
            var path = Path.Combine(Folder, "connection.log");
            if (File.Exists(path) && new FileInfo(path).Length > 131072)
                File.Move(path, path + ".previous", true);
            File.AppendAllText(path, $"{DateTimeOffset.Now:O} pid={Environment.ProcessId} {message}{Environment.NewLine}");
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }
    private readonly string Folder;
    private readonly string PathName;
    private readonly string BackupPath;
    private readonly string CachePath;
    private readonly string SeriesPath;
    private readonly string ViewPath;
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };

    public SettingsStore(string? folder = null)
    {
        Folder = folder ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".ai-calendar-desktop");
        PathName = Path.Combine(Folder, "settings.json");
        BackupPath = PathName + ".bak";
        CachePath = Path.Combine(Folder, "events.json");
        SeriesPath = Path.Combine(Folder, "series.json");
        ViewPath = Path.Combine(Folder, "view.json");
        if (folder is null) MigrateLegacyData();
    }

    private void MigrateLegacyData()
    {
        try
        {
            Directory.CreateDirectory(Folder);
            if (!HasUsablePairing(PathName) && !HasUsablePairing(BackupPath))
            {
                var source = LegacyFolders()
                    .SelectMany(path => new[] { Path.Combine(path, "settings.json"), Path.Combine(path, "settings.json.bak") })
                    .FirstOrDefault(HasUsablePairing);
                if (source is not null)
                {
                    File.Copy(source, PathName, true);
                    File.Copy(source, BackupPath, true);
                }
            }

            if (!File.Exists(CachePath))
            {
                var source = LegacyFolders()
                    .Select(path => Path.Combine(path, "events.json"))
                    .Where(File.Exists)
                    .Select(path => new FileInfo(path))
                    .OrderByDescending(file => file.LastWriteTimeUtc)
                    .FirstOrDefault();
                if (source is not null) source.CopyTo(CachePath, true);
            }
        }
        catch (IOException exception) { Log($"Legacy migration: {exception.GetType().Name}"); }
        catch (UnauthorizedAccessException) { Log("Legacy migration: access denied"); }
    }

    private static bool HasUsablePairing(string path)
    {
        try
        {
            var settings = JsonSerializer.Deserialize<DesktopSettings>(File.ReadAllText(path), JsonOptions);
            return !string.IsNullOrWhiteSpace(settings?.DeviceToken);
        }
        catch { return false; }
    }

    private static IEnumerable<string> LegacyFolders()
    {
        var profile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        var paths = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        {
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AI Calendar Desktop"),
            Path.Combine(profile, "AppData", "Local", "AI Calendar Desktop"),
        };
        var packages = Path.Combine(profile, "AppData", "Local", "Packages");
        if (Directory.Exists(packages))
        {
            foreach (var package in Directory.EnumerateDirectories(packages))
                paths.Add(Path.Combine(package, "LocalCache", "Local", "AI Calendar Desktop"));
        }
        return paths.Where(path => !string.Equals(path, Path.Combine(profile, ".ai-calendar-desktop"), StringComparison.OrdinalIgnoreCase));
    }

    public DesktopSettings Load()
    {
        DesktopSettings? fallback = null;
        foreach (var path in new[] { PathName, BackupPath })
        {
            try
            {
                var settings = JsonSerializer.Deserialize<DesktopSettings>(File.ReadAllText(path), JsonOptions)?.Normalize();
                if (settings is null) continue;
                fallback ??= settings;
                if (!string.IsNullOrWhiteSpace(settings.DeviceToken))
                {
                    Log($"Loaded valid pairing from {Path.GetFileName(path)}");
                    return settings;
                }
                Log($"{Path.GetFileName(path)}: credential missing or cannot be decrypted");
            }
            catch (IOException exception) { Log($"Settings read: {exception.GetType().Name}"); }
            catch (JsonException) { Log("Settings read: invalid JSON"); }
            catch (UnauthorizedAccessException) { Log("Settings read: access denied"); }
        }
        return fallback ?? new DesktopSettings();
    }

    public void Save(DesktopSettings settings)
    {
        Directory.CreateDirectory(Folder);
        var temporaryPath = PathName + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            using (var stream = new FileStream(temporaryPath, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                JsonSerializer.Serialize(stream, settings, JsonOptions);
                stream.Flush(true);
            }
            if (File.Exists(PathName))
                File.Replace(temporaryPath, PathName, BackupPath);
            else
                File.Move(temporaryPath, PathName);
            // Keep a recoverable copy of the current pairing, including the first save.
            File.Copy(PathName, BackupPath, true);
        }
        finally
        {
            if (File.Exists(temporaryPath)) File.Delete(temporaryPath);
        }
    }

    public ViewSettings LoadView()
    {
        try { return JsonSerializer.Deserialize<ViewSettings>(File.ReadAllText(ViewPath), JsonOptions)?.Normalize() ?? new ViewSettings(); }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException or JsonException) { return new ViewSettings(); }
    }

    /// <summary>Best effort: a view choice that cannot be saved is logged, never raised.</summary>
    public void SaveView(ViewSettings view)
    {
        var temporaryPath = ViewPath + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            Directory.CreateDirectory(Folder);
            File.WriteAllText(temporaryPath, JsonSerializer.Serialize(view, JsonOptions));
            File.Move(temporaryPath, ViewPath, true);
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            Log($"View settings save failed: {exception.GetType().Name}");
            try { File.Delete(temporaryPath); } catch (Exception cleanup) when (cleanup is IOException or UnauthorizedAccessException) { }
        }
    }

    public List<CalendarEvent> LoadEvents()
    {
        try
        {
            using var json = JsonDocument.Parse(File.ReadAllText(CachePath));
            var events = EventJson.ReadEvents(json.RootElement, out var skipped);
            if (skipped > 0) Log($"Event cache: skipped {skipped} unreadable events");
            return events;
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException or JsonException) { return []; }
    }

    /// <summary>The repeating reminders of the last sync, so details can word the rule before the next one.</summary>
    public List<ReminderSeries> LoadSeries()
    {
        try
        {
            using var json = JsonDocument.Parse(File.ReadAllText(SeriesPath));
            return ReminderSeries.ReadAll(json.RootElement);
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException or JsonException) { return []; }
    }

    public void SaveSeries(IEnumerable<ReminderSeries> series)
    {
        Directory.CreateDirectory(Folder);
        var temporaryPath = SeriesPath + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            File.WriteAllText(temporaryPath, JsonSerializer.Serialize(series, JsonOptions));
            File.Move(temporaryPath, SeriesPath, true);
        }
        finally
        {
            if (File.Exists(temporaryPath)) File.Delete(temporaryPath);
        }
    }

    public void SaveEvents(IEnumerable<CalendarEvent> events)
    {
        Directory.CreateDirectory(Folder);
        // Write-then-rename so a crash or power loss mid-write cannot leave a truncated cache.
        var temporaryPath = CachePath + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            using (var stream = new FileStream(temporaryPath, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                JsonSerializer.Serialize(stream, events, JsonOptions);
                stream.Flush(true);
            }
            File.Move(temporaryPath, CachePath, true);
        }
        finally
        {
            if (File.Exists(temporaryPath)) File.Delete(temporaryPath);
        }
    }
}
