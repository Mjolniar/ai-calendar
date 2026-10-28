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

    /// <summary>
    /// Marks a reminder done or not done, and nothing else. The PC's copy can be up to a minute old,
    /// so it never sends the rest of the reminder: that would undo a newer change made elsewhere.
    /// </summary>
    public virtual async Task SetDoneAsync(CalendarEvent item, bool done, CancellationToken cancellationToken = default)
    {
        using var request = Request(HttpMethod.Post, $"/v1/events/{Uri.EscapeDataString(item.Id)}/{(done ? "done" : "undone")}");
        request.Content = JsonContent.Create(new { });
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

    // ------------------------------------------------------------ kitchen (bridge 0.9.0)

    /// <summary>The shopping list, pantry, this week's and next week's meal plan and the dinner notes.</summary>
    public virtual async Task<KitchenSnapshot> GetKitchenAsync(CancellationToken cancellationToken = default)
    {
        using var json = await SendKitchenAsync(HttpMethod.Get, "/v1/kitchen", null, cancellationToken);
        return KitchenJson.ReadSnapshot(json.Document.RootElement);
    }

    /// <summary>Adds a list item. An open item of the same name comes back instead (Created false).</summary>
    public virtual async Task<ShoppingItemResult> AddKitchenItemAsync(string name, string? qty = null, string? note = null,
        CancellationToken cancellationToken = default)
    {
        using var json = await SendKitchenAsync(HttpMethod.Post, "/v1/kitchen/items", KitchenJson.AddItemBody(name, qty, note), cancellationToken);
        return KitchenJson.ReadItemResult(json.Document.RootElement, json.Status == HttpStatusCode.Created);
    }

    /// <summary>Changes the fields given; status is need or got.</summary>
    public virtual async Task<ShoppingItemResult> UpdateKitchenItemAsync(string id, ShoppingItemChange change,
        CancellationToken cancellationToken = default)
    {
        using var json = await SendKitchenAsync(HttpMethod.Patch, $"/v1/kitchen/items/{KitchenId(id)}", KitchenJson.Body(change), cancellationToken);
        return KitchenJson.ReadItemResult(json.Document.RootElement, createdByStatus: false);
    }

    /// <summary>Removes a list item. Returns the new revision.</summary>
    public virtual async Task<long> DeleteKitchenItemAsync(string id, CancellationToken cancellationToken = default)
    {
        using var json = await SendKitchenAsync(HttpMethod.Delete, $"/v1/kitchen/items/{KitchenId(id)}", null, cancellationToken);
        return KitchenJson.Revision(json.Document.RootElement);
    }

    /// <summary>
    /// Corrects Claude's pantry estimate (bridge 0.10.0). "out" and "have" name the item and apply at
    /// once ("out" also lists it); a "note" carries text, optionally about an item, and waits for Claude.
    /// (The older pantry routes stay on the server for older apps; this app no longer uses them.)
    /// </summary>
    public virtual async Task<CorrectionResult> AddCorrectionAsync(string kind, string? text = null, string? itemId = null,
        CancellationToken cancellationToken = default)
    {
        var body = KitchenJson.CorrectionBody(kind, text, itemId is null ? null : KitchenId(itemId));
        using var json = await SendKitchenAsync(HttpMethod.Post, "/v1/kitchen/corrections", body, cancellationToken);
        return KitchenJson.ReadCorrectionResult(json.Document.RootElement);
    }

    /// <summary>Withdraws an open note. Returns the new revision.</summary>
    public virtual async Task<long> WithdrawCorrectionAsync(string id, CancellationToken cancellationToken = default)
    {
        using var json = await SendKitchenAsync(HttpMethod.Delete, $"/v1/kitchen/corrections/{KitchenId(id)}", null, cancellationToken);
        return KitchenJson.Revision(json.Document.RootElement);
    }

    /// <summary>Sets how the dinner of <paramref name="date"/> (YYYY-MM-DD) went; only the fields given change.</summary>
    public virtual async Task<DinnerNoteResult> PutDinnerNoteAsync(string date, DinnerNoteChange change,
        CancellationToken cancellationToken = default)
    {
        if (!KitchenJson.IsValidDate(date)) throw new ArgumentException("A dinner date is YYYY-MM-DD.", nameof(date));
        using var json = await SendKitchenAsync(HttpMethod.Put, $"/v1/kitchen/notes/{date}", KitchenJson.Body(change), cancellationToken);
        return KitchenJson.ReadNoteResult(json.Document.RootElement, date);
    }

    /// <summary>Kitchen ids are letters, digits, _ and -; anything else would never reach the right record.</summary>
    internal static string KitchenId(string id) =>
        KitchenJson.IsValidId(id) ? id : throw new ArgumentException("Not a kitchen id.", nameof(id));

    private sealed class KitchenAnswer(JsonDocument document, HttpStatusCode status) : IDisposable
    {
        public JsonDocument Document { get; } = document;
        public HttpStatusCode Status { get; } = status;
        public void Dispose() => Document.Dispose();
    }

    private async Task<KitchenAnswer> SendKitchenAsync(HttpMethod method, string path, Dictionary<string, string>? body,
        CancellationToken cancellationToken)
    {
        using var request = Request(method, path);
        if (body is not null) request.Content = JsonContent.Create(body);
        using var response = await Http.SendAsync(request, cancellationToken);
        await EnsureSuccess(response, cancellationToken);
        var text = await response.Content.ReadAsStringAsync(cancellationToken);
        try { return new KitchenAnswer(JsonDocument.Parse(text), response.StatusCode); }
        catch (JsonException) { throw new FormatException("The calendar service sent an answer that could not be read."); }
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
