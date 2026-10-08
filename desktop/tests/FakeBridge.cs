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

    public static HttpRequestException Offline() => new("No such host is known.");

    private static List<CalendarEvent> Copy(IEnumerable<CalendarEvent> events)
    {
        using var json = JsonDocument.Parse(JsonSerializer.Serialize(events));
        return EventJson.ReadEvents(json.RootElement, out _);
    }
}
