using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Net.Http;
using System.Text.Json;

namespace AICalendar.Desktop;

/// <summary>Non-success response from the calendar service, with the server's own error text.</summary>
public sealed class BridgeException(HttpStatusCode statusCode, string serverMessage)
    : HttpRequestException(DescribeStatus(statusCode, serverMessage), null, statusCode)
{
    public string ServerMessage { get; } = serverMessage;

    private static string DescribeStatus(HttpStatusCode statusCode, string serverMessage) => (int)statusCode switch
    {
        400 => $"The calendar service rejected the change: {serverMessage}",
        401 or 403 => "This PC is no longer authorized. Pair it again under Tools > Cloud Connection.",
        404 => $"Not found on the calendar service: {serverMessage}",
        429 => "The calendar service is rate limiting requests. Try again shortly.",
        _ => $"Calendar service returned {(int)statusCode}: {serverMessage}",
    };
}

/// <summary>Talks to the calendar service. The methods are virtual so the window tests can stand in for it.</summary>
public class BridgeClient
{
    // One process-wide client; the pooled-connection lifetime lets DNS changes take effect
    // without the socket exhaustion that per-request HttpClient instances cause.
    private static readonly HttpClient Http = new(new SocketsHttpHandler
    {
        PooledConnectionLifetime = TimeSpan.FromMinutes(10),
        ConnectTimeout = TimeSpan.FromSeconds(15),
    })
    { Timeout = TimeSpan.FromSeconds(35) };
    private readonly DesktopSettings _settings;

    public BridgeClient(DesktopSettings settings) => _settings = settings;

    public async Task<string> PairAsync(string pairingCode, CancellationToken cancellationToken = default)
    {
        using var response = await Http.PostAsJsonAsync(Url("/v1/devices/register"), new
        {
            pairingCode,
            installationId = _settings.ClientId,
            deviceName = $"Windows desktop ({Environment.MachineName})",
            pushEnabled = false,
        }, cancellationToken);
        await EnsureSuccess(response, cancellationToken);
        using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancellationToken));
        return json.RootElement.ValueKind == JsonValueKind.Object &&
               json.RootElement.TryGetProperty("deviceToken", out var token) &&
               token.ValueKind == JsonValueKind.String &&
               !string.IsNullOrWhiteSpace(token.GetString())
            ? token.GetString()!
            : throw new InvalidOperationException("Pairing returned no device token.");
    }

    public virtual async Task<SyncEnvelope> SyncAsync(CancellationToken cancellationToken = default)
    {
        using var request = Request(HttpMethod.Get, "/v1/sync");
        using var response = await Http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        await EnsureSuccess(response, cancellationToken);
        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
        using var json = await JsonDocument.ParseAsync(stream, default, cancellationToken);
        var envelope = new SyncEnvelope();
        if (json.RootElement.ValueKind != JsonValueKind.Object) return envelope;
        foreach (var property in json.RootElement.EnumerateObject())
        {
            if (property.NameEquals("revision") && property.Value.TryGetInt64(out var revision)) envelope.Revision = revision;
            else if (property.Name.Equals("events", StringComparison.OrdinalIgnoreCase))
            {
                // Per-event parsing: one malformed record is skipped instead of failing the whole feed.
                envelope.Events = EventJson.ReadEvents(property.Value, out var skipped);
                envelope.SkippedEvents = skipped;
            }
            else if (property.Name.Equals("series", StringComparison.OrdinalIgnoreCase))
            {
                envelope.Series = ReminderSeries.ReadAll(property.Value);
            }
        }
        return envelope;
    }

    public virtual async Task SetDoneAsync(CalendarEvent item, bool done, CancellationToken cancellationToken = default)
    {
        using var request = Request(HttpMethod.Patch, $"/v1/events/{Uri.EscapeDataString(item.Id)}");
        request.Content = JsonContent.Create(new
        {
            title = item.Title,
            startAt = item.StartAt,
            timeZone = item.TimeZone,
            location = item.Location,
            notes = item.Notes,
            critical = item.Critical,
            done,
            domain = item.ResolvedDomain,
            category = item.Category,
            reminderOffsetsMinutes = item.ReminderOffsetsMinutes,
        });
        using var response = await Http.SendAsync(request, cancellationToken);
        await EnsureSuccess(response, cancellationToken);
    }

    public virtual async Task DeleteAsync(string id, CancellationToken cancellationToken = default)
    {
        using var request = Request(HttpMethod.Delete, $"/v1/events/{Uri.EscapeDataString(id)}");
        using var response = await Http.SendAsync(request, cancellationToken);
        await EnsureSuccess(response, cancellationToken);
    }

    /// <summary>
    /// Deletes a repeating reminder: from <paramref name="from"/> on (this and all future ones), or
    /// the whole series when it is null.
    /// </summary>
    public virtual async Task DeleteSeriesAsync(string seriesId, string? from, CancellationToken cancellationToken = default)
    {
        var query = string.IsNullOrWhiteSpace(from) ? "" : $"?from={Uri.EscapeDataString(from)}";
        using var request = Request(HttpMethod.Delete, $"/v1/series/{Uri.EscapeDataString(seriesId)}{query}");
        using var response = await Http.SendAsync(request, cancellationToken);
        await EnsureSuccess(response, cancellationToken);
    }

    private HttpRequestMessage Request(HttpMethod method, string path)
    {
        var token = _settings.DeviceToken ?? throw new InvalidOperationException("Pair this desktop first.");
        var request = new HttpRequestMessage(method, Url(path));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        return request;
    }

    private Uri Url(string path) => new($"{_settings.ServerUrl.Trim().TrimEnd('/')}{path}");

    private static async Task EnsureSuccess(HttpResponseMessage response, CancellationToken cancellationToken)
    {
        if (response.IsSuccessStatusCode) return;
        string body;
        try { body = await response.Content.ReadAsStringAsync(cancellationToken); }
        catch (HttpRequestException) { body = ""; }
        throw new BridgeException(response.StatusCode, ExtractError(body, response.ReasonPhrase));
    }

    private static string ExtractError(string body, string? reasonPhrase)
    {
        try
        {
            using var json = JsonDocument.Parse(body);
            if (json.RootElement.ValueKind == JsonValueKind.Object)
            {
                foreach (var name in new[] { "error", "message" })
                {
                    if (json.RootElement.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String &&
                        !string.IsNullOrWhiteSpace(value.GetString()))
                        return Clip(value.GetString()!);
                }
            }
        }
        catch (JsonException) { }
        return string.IsNullOrWhiteSpace(body) ? reasonPhrase ?? "request failed" : Clip(body.Trim());
    }

    private static string Clip(string value) => value.Length > 200 ? value[..200] + "..." : value;
}
