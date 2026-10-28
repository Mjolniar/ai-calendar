using System.Net;
using System.Net.Http;
using System.Text.Json;

namespace AICalendar.Desktop.Tests;

/// <summary>
/// Stands in for the calendar service. It keeps its own copy of the reminders, applies what the
/// window asks for, answers at once (kitchen fetches only when <see cref="ReleaseFetch"/> says, while
/// <see cref="HoldFetches"/> is set), and fails with <see cref="Failure"/> while one is set.
/// </summary>
internal sealed class FakeBridge(IEnumerable<CalendarEvent>? events = null) : BridgeClient(new DesktopSettings())
{
    public List<string> Calls { get; } = [];
    public List<CalendarEvent> Events { get; set; } = Copy(events ?? []);
    public List<ReminderSeries>? Series { get; set; }
    public Exception? Failure { get; set; }

    public override Task<SyncEnvelope> SyncAsync(CancellationToken cancellationToken = default)
    {
        Calls.Add("sync");
        // Fresh objects each time, as a real sync deserializes them.
        return Failure is null ? Task.FromResult(new SyncEnvelope { Events = Copy(Events), Series = Series }) : Task.FromException<SyncEnvelope>(Failure);
    }

    public override Task SetDoneAsync(CalendarEvent item, bool done, CancellationToken cancellationToken = default)
    {
        Calls.Add($"done {item.Id} {done}");
        if (Failure is not null) return Task.FromException(Failure);
        foreach (var match in Events.Where(existing => existing.Id == item.Id))
        {
            match.Done = done;
            match.Revision++;
        }
        return Task.CompletedTask;
    }

    public override Task DeleteAsync(string id, CancellationToken cancellationToken = default)
    {
        Calls.Add($"delete {id}");
        if (Failure is not null) return Task.FromException(Failure);
        Events.RemoveAll(existing => existing.Id == id);
        return Task.CompletedTask;
    }

    /// <summary>Like the bridge: unfinished occurrences from <paramref name="from"/> on go, or the whole series' future ones.</summary>
    public override Task DeleteSeriesAsync(string seriesId, string? from, CancellationToken cancellationToken = default)
    {
        Calls.Add($"delete series {seriesId} from {from ?? "start"}");
        if (Failure is not null) return Task.FromException(Failure);
        Events.RemoveAll(existing => existing.SeriesId == seriesId && !existing.Done &&
            (from is null || string.CompareOrdinal(existing.OccurrenceDate, from) >= 0));
        if (from is null) Series?.RemoveAll(series => series.Id == seriesId);
        return Task.CompletedTask;
    }

    // ------------------------------------------------------------ kitchen

    /// <summary>
    /// The kitchen as the service holds it. Its server rules are played here as docs\kitchen.md
    /// states them, as bridge 0.11.0 has them, or as 0.10.0 had them while <see cref="OldBridge"/> is set.
    /// </summary>
    public KitchenSnapshot Kitchen { get; set; } = new();

    /// <summary>The service's clock, for doneAt and updatedAt.</summary>
    public DateTimeOffset KitchenNow { get; set; } = KitchenSample.Now;

    /// <summary>
    /// Bridge 0.10.0, the one deployed before 0.11.0: Undo's restore and a correction's clientId are
    /// ignored, a note about an item that is gone is a 404, and "we have it" leaves the list alone.
    /// </summary>
    public bool OldBridge { get; set; }

    /// <summary>The clientId each correction came with, in order (null when none was sent).</summary>
    public List<string?> CorrectionClientIds { get; } = [];

    /// <summary>
    /// The next correction is stored and then fails with this, as an answer lost on the way back
    /// does (a timeout). Cleared once used.
    /// </summary>
    public Exception? LoseNextCorrectionAnswer { get; set; }

    /// <summary>
    /// While set, every kitchen fetch is held until <see cref="ReleaseFetch"/>: it answers with the
    /// kitchen as it was when it was asked, as a slow connection would.
    /// </summary>
    public bool HoldFetches { get; set; }

    /// <summary>How many fetches are waiting for <see cref="ReleaseFetch"/>.</summary>
    public int HeldFetches => _held.Count;

    private readonly List<(TaskCompletionSource<KitchenSnapshot> Answer, KitchenSnapshot State)> _held = [];
    private readonly Dictionary<string, string> _correctionsByClientId = new(StringComparer.Ordinal);
    private int _nextKitchenId = 1;

    /// <summary>Lets the oldest held fetch answer.</summary>
    public void ReleaseFetch()
    {
        var (answer, state) = _held[0];
        _held.RemoveAt(0);
        answer.SetResult(state);
    }

    public override Task<KitchenSnapshot> GetKitchenAsync(CancellationToken cancellationToken = default)
    {
        Calls.Add("kitchen");
        if (Failure is not null) return Task.FromException<KitchenSnapshot>(Failure);
        // A fresh copy each time, as a real fetch is.
        var state = Kitchen.Copy();
        if (OldBridge) state.Today = null;
        if (!HoldFetches) return Task.FromResult(state);
        var answer = new TaskCompletionSource<KitchenSnapshot>();
        _held.Add((answer, state));
        return answer.Task;
    }

    /// <summary>
    /// Rule 1: an open item of the same name comes back instead of a second one (a qty or note sent
    /// with it replaces the stored one). Restore (Undo, 0.11.0) puts the line back as it was, on order
    /// or bought; an open one of the same name comes back unchanged instead, except for a bought one,
    /// which is always put back. 0.10.0 ignores restore and adds it to buy.
    /// </summary>
    public override Task<ShoppingItemResult> AddKitchenItemAsync(string name, string? qty = null, string? note = null,
        ShoppingItemRestore? restore = null, CancellationToken cancellationToken = default)
    {
        Calls.Add($"add {name}" + (restore is null ? "" : $" as {restore.Status}" + (restore.OrderRef.Length > 0 ? $" [{restore.OrderRef}]" : "")));
        if (Failure is not null) return Task.FromException<ShoppingItemResult>(Failure);
        if (OldBridge) restore = null;
        if (restore is not null && restore.Status is not (ShoppingStatus.Need or ShoppingStatus.Ordered or ShoppingStatus.Got))
            return Task.FromException<ShoppingItemResult>(new BridgeException(HttpStatusCode.BadRequest, "status must be need, ordered or got"));
        var status = restore?.Status ?? ShoppingStatus.Need;
        var key = KitchenText.NameKey(name);
        var index = status == ShoppingStatus.Got ? -1 : Kitchen.List.FindIndex(item => item.IsOpen && KitchenText.NameKey(item.Name) == key);
        if (index >= 0 && restore is not null) return Task.FromResult(new ShoppingItemResult(Kitchen.Revision, Kitchen.List[index], Created: false));
        Kitchen.Revision++;
        if (index >= 0)
        {
            var open = Kitchen.List[index];
            Kitchen.List[index] = open with { Qty = qty ?? open.Qty, Note = note ?? open.Note, UpdatedAt = KitchenNow };
            return Task.FromResult(new ShoppingItemResult(Kitchen.Revision, Kitchen.List[index], Created: false));
        }
        var added = new ShoppingItem
        {
            Id = $"knew{_nextKitchenId++:D8}",
            Name = name.Trim(),
            Qty = qty ?? "",
            Note = note ?? "",
            Status = status,
            OrderRef = status == ShoppingStatus.Need ? "" : restore!.OrderRef,
            Source = "device",
            AddedBy = DeviceName,
            AddedAt = KitchenNow,
            UpdatedAt = KitchenNow,
            DoneAt = status == ShoppingStatus.Got ? KitchenNow : null,
        };
        Kitchen.List.Add(added);
        return Task.FromResult(new ShoppingItemResult(Kitchen.Revision, added, Created: true));
    }

    /// <summary>Rule 3: an item that becomes got puts the pantry item of the same name back to have.</summary>
    public override Task<ShoppingItemResult> UpdateKitchenItemAsync(string id, ShoppingItemChange change,
        CancellationToken cancellationToken = default)
    {
        Calls.Add($"item {id} {change.Status}");
        if (Failure is not null) return Task.FromException<ShoppingItemResult>(Failure);
        var index = Kitchen.List.FindIndex(item => item.Id == id);
        if (index < 0) return Task.FromException<ShoppingItemResult>(new BridgeException(HttpStatusCode.NotFound, "Item not found"));
        var item = Kitchen.List[index];
        var status = change.Status ?? item.Status;
        item = item with
        {
            Name = change.Name ?? item.Name,
            Qty = change.Qty ?? item.Qty,
            Note = change.Note ?? item.Note,
            Status = status,
            DoneAt = status == ShoppingStatus.Got ? KitchenNow : null,
            UpdatedAt = KitchenNow,
        };
        Kitchen.List[index] = item;
        if (status == ShoppingStatus.Got)
        {
            var key = KitchenText.NameKey(item.Name);
            for (var pantry = 0; pantry < Kitchen.Pantry.Count; pantry++)
            {
                if (KitchenText.NameKey(Kitchen.Pantry[pantry].Name) == key)
                    Kitchen.Pantry[pantry] = Kitchen.Pantry[pantry] with { Status = PantryStatus.Have, UpdatedAt = KitchenNow };
            }
        }
        Kitchen.Revision++;
        return Task.FromResult(new ShoppingItemResult(Kitchen.Revision, item, Created: false));
    }

    public override Task<long> DeleteKitchenItemAsync(string id, CancellationToken cancellationToken = default)
    {
        Calls.Add($"remove {id}");
        if (Failure is not null) return Task.FromException<long>(Failure);
        if (Kitchen.List.RemoveAll(item => item.Id == id) == 0)
            return Task.FromException<long>(new BridgeException(HttpStatusCode.NotFound, "Item not found"));
        return Task.FromResult(++Kitchen.Revision);
    }

    /// <summary>
    /// Corrections as the bridge handles them. Out and have need a known item and apply at once: out
    /// makes sure it is on the list (rule 2) and says so in the reply; have takes a pantry-added line
    /// for it off the list (0.11.0). A note needs text and stays open; about an item that is gone it
    /// is kept without the item (0.11.0) or refused with a 404 (0.10.0). The same clientId again
    /// answers with the stored correction and changes nothing (0.11.0).
    /// </summary>
    public override Task<CorrectionResult> AddCorrectionAsync(string kind, string? text = null, string? itemId = null,
        string? clientId = null, CancellationToken cancellationToken = default)
    {
        Calls.Add($"correction {kind}" + (itemId is null ? "" : $" {itemId}") + (text is null ? "" : $" \"{text}\""));
        CorrectionClientIds.Add(clientId);
        if (Failure is not null) return Task.FromException<CorrectionResult>(Failure);
        if (kind == CorrectionKind.Note && string.IsNullOrWhiteSpace(text))
            return Task.FromException<CorrectionResult>(new BridgeException(HttpStatusCode.BadRequest, "A note needs text (1-300 characters)"));
        if (kind != CorrectionKind.Note && itemId is null)
            return Task.FromException<CorrectionResult>(new BridgeException(HttpStatusCode.BadRequest, "out and have need the pantry item's itemId"));
        if (!OldBridge && clientId is not null && _correctionsByClientId.TryGetValue(clientId, out var storedId) &&
            Kitchen.Corrections.FirstOrDefault(existing => existing.Id == storedId) is { } stored)
        {
            var current = stored.ItemId is null ? null : Kitchen.Pantry.FirstOrDefault(existing => existing.Id == stored.ItemId);
            return Task.FromResult(new CorrectionResult(Kitchen.Revision, stored, current, null, Repeated: true));
        }
        PantryItem? item = null;
        ShoppingItem? listItem = null;
        string? removedListItemId = null;
        var index = itemId is null ? -1 : Kitchen.Pantry.FindIndex(existing => existing.Id == itemId);
        if (itemId is not null && index < 0 && (kind != CorrectionKind.Note || OldBridge))
            return Task.FromException<CorrectionResult>(new BridgeException(HttpStatusCode.NotFound, "Pantry item not found"));
        if (index >= 0)
        {
            item = Kitchen.Pantry[index];
            if (kind != CorrectionKind.Note)
            {
                item = item with
                {
                    Status = kind == CorrectionKind.Out ? PantryStatus.Out : PantryStatus.Have,
                    Basis = kind == CorrectionKind.Out ? $"{DeviceName} said it's out" : $"{DeviceName} said you have it",
                    UpdatedAt = KitchenNow,
                };
                Kitchen.Pantry[index] = item;
                var key = KitchenText.NameKey(item.Name);
                if (kind == CorrectionKind.Out && !Kitchen.List.Any(line => line.IsOpen && KitchenText.NameKey(line.Name) == key))
                {
                    listItem = new ShoppingItem
                    {
                        Id = $"knew{_nextKitchenId++:D8}",
                        Name = item.Name,
                        Status = ShoppingStatus.Need,
                        Source = "pantry",
                        AddedBy = "Pantry",
                        AddedAt = KitchenNow,
                        UpdatedAt = KitchenNow,
                    };
                    Kitchen.List.Add(listItem);
                }
                if (kind == CorrectionKind.Have && !OldBridge &&
                    Kitchen.List.FirstOrDefault(line => line.Status == ShoppingStatus.Need && line.Source == "pantry" && KitchenText.NameKey(line.Name) == key) is { } listed)
                {
                    Kitchen.List.Remove(listed);
                    removedListItemId = listed.Id;
                }
            }
        }
        var correction = new KitchenCorrection
        {
            Id = $"cnew{_nextKitchenId++:D8}",
            Kind = kind,
            Text = text?.Trim() ?? "",
            ItemId = item?.Id,
            ItemName = item?.Name ?? "",
            Status = kind == CorrectionKind.Note ? CorrectionStatus.Open : CorrectionStatus.Applied,
            Reply = kind switch
            {
                CorrectionKind.Out => listItem is not null ? "Marked out and added to the shopping list." : "Marked out; it was already on the shopping list.",
                CorrectionKind.Have => removedListItemId is not null ? "Marked as on hand and taken off the shopping list."
                    : item is not null && Kitchen.List.Any(line => line.IsOpen && KitchenText.NameKey(line.Name) == KitchenText.NameKey(item.Name))
                        ? "Marked as on hand; it's still on the shopping list." : "Marked as on hand.",
                _ => "",
            },
            AddedBy = DeviceName,
            AddedAt = KitchenNow,
            ResolvedAt = kind == CorrectionKind.Note ? null : KitchenNow,
        };
        Kitchen.Corrections.Add(correction);
        if (!OldBridge && clientId is not null) _correctionsByClientId[clientId] = correction.Id;
        var result = new CorrectionResult(++Kitchen.Revision, correction, item, listItem, removedListItemId);
        if (LoseNextCorrectionAnswer is { } lost)
        {
            LoseNextCorrectionAnswer = null;
            return Task.FromException<CorrectionResult>(lost);
        }
        return Task.FromResult(result);
    }

    /// <summary>Only an open note can be withdrawn; anything else is a 404.</summary>
    public override Task<long> WithdrawCorrectionAsync(string id, CancellationToken cancellationToken = default)
    {
        Calls.Add($"withdraw {id}");
        if (Failure is not null) return Task.FromException<long>(Failure);
        if (Kitchen.Corrections.RemoveAll(item => item.Id == id && item.IsOpen) == 0)
            return Task.FromException<long>(new BridgeException(HttpStatusCode.NotFound, "No open correction with that id"));
        return Task.FromResult(++Kitchen.Revision);
    }

    /// <summary>What the service calls this PC.</summary>
    public const string DeviceName = "Windows desktop (TEST-PC)";

    public override Task<DinnerNoteResult> PutDinnerNoteAsync(string date, DinnerNoteChange change,
        CancellationToken cancellationToken = default)
    {
        var fields = KitchenJson.Body(change).Select(field => $"{field.Key}={field.Value}");
        Calls.Add($"note {date} {string.Join(" ", fields)}");
        if (Failure is not null) return Task.FromException<DinnerNoteResult>(Failure);
        var note = Kitchen.Notes.TryGetValue(date, out var stored) ? stored : DinnerNote.Empty;
        note = note with
        {
            Cooked = change.Cooked ?? note.Cooked,
            Rating = change.Rating ?? note.Rating,
            Note = change.Note?.Trim() ?? note.Note,
            UpdatedAt = KitchenNow,
        };
        Kitchen.Notes[date] = note;
        return Task.FromResult(new DinnerNoteResult(++Kitchen.Revision, date, note));
    }

    public static HttpRequestException Offline() => new("No such host is known.");

    private static List<CalendarEvent> Copy(IEnumerable<CalendarEvent> events)
    {
        using var json = JsonDocument.Parse(JsonSerializer.Serialize(events));
        return EventJson.ReadEvents(json.RootElement, out _);
    }
}
