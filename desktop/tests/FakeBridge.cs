using System.Net;
using System.Net.Http;
using System.Text.Json;

namespace AICalendar.Desktop.Tests;

/// <summary>
/// Stands in for the calendar service. It keeps its own copy of the reminders, applies what the
/// window asks for, answers at once, and fails with <see cref="Failure"/> while one is set.
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

    /// <summary>The kitchen as the service holds it. Its server rules are played here as docs\kitchen.md states them.</summary>
    public KitchenSnapshot Kitchen { get; set; } = new();

    /// <summary>The service's clock, for doneAt and updatedAt.</summary>
    public DateTimeOffset KitchenNow { get; set; } = KitchenSample.Now;

    private int _nextKitchenId = 1;

    public override Task<KitchenSnapshot> GetKitchenAsync(CancellationToken cancellationToken = default)
    {
        Calls.Add("kitchen");
        // A fresh copy each time, as a real fetch is.
        return Failure is null ? Task.FromResult(Kitchen.Copy()) : Task.FromException<KitchenSnapshot>(Failure);
    }

    /// <summary>Rule 1: an open item of the same name comes back instead of a second one.</summary>
    public override Task<ShoppingItemResult> AddKitchenItemAsync(string name, string? qty = null, string? note = null,
        CancellationToken cancellationToken = default)
    {
        Calls.Add($"add {name}");
        if (Failure is not null) return Task.FromException<ShoppingItemResult>(Failure);
        var key = KitchenText.NameKey(name);
        var index = Kitchen.List.FindIndex(item => item.IsOpen && KitchenText.NameKey(item.Name) == key);
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
            Status = ShoppingStatus.Need,
            Source = "device",
            AddedBy = DeviceName,
            AddedAt = KitchenNow,
            UpdatedAt = KitchenNow,
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
    /// Corrections as bridge 0.10.0 handles them: out and have need a known item and apply at once
    /// (out puts it on the list by rule 2, and says so in the reply); a note needs text and stays open.
    /// </summary>
    public override Task<CorrectionResult> AddCorrectionAsync(string kind, string? text = null, string? itemId = null,
        CancellationToken cancellationToken = default)
    {
        Calls.Add($"correction {kind}" + (itemId is null ? "" : $" {itemId}") + (text is null ? "" : $" \"{text}\""));
        if (Failure is not null) return Task.FromException<CorrectionResult>(Failure);
        if (kind == CorrectionKind.Note && string.IsNullOrWhiteSpace(text))
            return Task.FromException<CorrectionResult>(new BridgeException(HttpStatusCode.BadRequest, "A note needs text (1-300 characters)"));
        if (kind != CorrectionKind.Note && itemId is null)
            return Task.FromException<CorrectionResult>(new BridgeException(HttpStatusCode.BadRequest, "out and have need the pantry item's itemId"));
        PantryItem? item = null;
        ShoppingItem? listItem = null;
        if (itemId is not null)
        {
            var index = Kitchen.Pantry.FindIndex(existing => existing.Id == itemId);
            if (index < 0) return Task.FromException<CorrectionResult>(new BridgeException(HttpStatusCode.NotFound, "Pantry item not found"));
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
                CorrectionKind.Have => "Marked as on hand.",
                _ => "",
            },
            AddedBy = DeviceName,
            AddedAt = KitchenNow,
            ResolvedAt = kind == CorrectionKind.Note ? null : KitchenNow,
        };
        Kitchen.Corrections.Add(correction);
        return Task.FromResult(new CorrectionResult(++Kitchen.Revision, correction, item, listItem));
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
