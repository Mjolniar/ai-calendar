using System.Text.Json;
using AICalendar.Desktop.Xp;

namespace AICalendar.Desktop.Tests;

/// <summary>Repeating reminders: the rule in words, the row and its details, and deleting one or all future ones.</summary>
[Collection(UiCollection.Name)]
public sealed class RepeatTests(UiFixture ui)
{
    private static RepeatRule Rule(string freq, int interval = 1, int[]? weekdays = null, string start = "2026-10-09", string time = "08:00",
        string? until = null, int? count = null) => new()
    {
        Freq = freq,
        Interval = interval,
        Weekdays = [.. weekdays ?? []],
        StartDate = start,
        Time = time,
        Until = until,
        Count = count,
    };

    /// <summary>The same rules and words as RepeatRuleTest.kt on the phone.</summary>
    [Fact]
    public void RuleInWordsMatchesThePhone()
    {
        Assert.Equal("Every week on Friday at 8:00 AM", RepeatText.Describe(Rule("weekly")));
        Assert.Equal("Every 2 weeks on Monday, Wednesday and Friday at 5:30 PM", RepeatText.Describe(Rule("weekly", 2, [5, 1, 3], time: "17:30")));
        Assert.Equal("Every day at 8:00 AM", RepeatText.Describe(Rule("daily")));
        Assert.Equal("Every 3 days at 8:00 AM, 10 times", RepeatText.Describe(Rule("daily", 3, count: 10)));
        Assert.Equal("Every month on the 31st at 8:00 AM", RepeatText.Describe(Rule("monthly", start: "2026-01-31")));
        Assert.Equal("Every 6 months on the 2nd at 12:00 PM, 1 time", RepeatText.Describe(Rule("monthly", 6, start: "2026-01-02", time: "12:00", count: 1)));
        Assert.Equal("Every year on October 9 at 8:00 AM, until Dec 4, 2030", RepeatText.Describe(Rule("yearly", until: "2030-12-04")));
        Assert.Equal(["1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "23rd", "31st"],
            new[] { 1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 31 }.Select(RepeatText.Ordinal));
        Assert.Equal("Repeats", RepeatText.Describe(Rule("hourly")));
    }

    [Fact]
    public void OccurrenceFieldsAndSeriesAreRead()
    {
        using var events = JsonDocument.Parse("""
            [{"id":"o1","title":"Trash","startAt":"2026-10-09T14:00:00.000Z","seriesId":"s1","occurrenceDate":"2026-10-09","detached":true},
             {"id":"p1","title":"Dentist","startAt":"2026-10-09T14:00:00.000Z"}]
            """);
        var read = EventJson.ReadEvents(events.RootElement, out var skipped);
        Assert.Equal(0, skipped);
        Assert.True(read[0].IsOccurrence);
        Assert.Equal("2026-10-09", read[0].OccurrenceDate);
        Assert.True(read[0].Detached);
        Assert.False(read[1].IsOccurrence);

        using var series = JsonDocument.Parse("""
            [{"id":"s1","title":"Trash","timeZone":"America/Denver","rule":{"freq":"weekly","interval":1,"weekdays":[5],"startDate":"2026-10-09","time":"08:00","count":9},"skippedDates":["2026-10-16"]},
             {"id":"","title":"No id"}, "not an object"]
            """);
        var list = ReminderSeries.ReadAll(series.RootElement);
        Assert.Equal(["s1"], list.Select(item => item.Id));
        Assert.Equal(9, list[0].Rule.Count);
        Assert.Equal([5], list[0].Rule.Weekdays);
    }

    [Fact]
    public void TheSeriesCacheSurvivesARestart()
    {
        var store = Sample.Store(events: []);
        store.SaveSeries([Trash()]);
        Assert.Equal("Every week on Friday at 8:00 AM", RepeatText.Describe(store.LoadSeries().Single().Rule));
    }

    [Fact]
    public void DetailsSayHowItRepeats()
    {
        var occurrence = Occurrence("t1", "2026-10-02");
        var row = new ReminderRow(occurrence, Sample.Now, DateOnly.FromDateTime(Sample.Now.Date), archivedView: false, showDate: true,
            repeatText: "Every week on Friday at 8:00 AM");
        Assert.True(row.IsRepeating);
        Assert.Contains(new DetailLine("Repeats", "Every week on Friday at 8:00 AM"), row.Details);

        occurrence.Detached = true;
        var edited = new ReminderRow(occurrence, Sample.Now, DateOnly.FromDateTime(Sample.Now.Date), archivedView: false, showDate: true);
        Assert.Contains(new DetailLine("Repeats", "Yes. This one was changed on its own."), edited.Details);

        var plain = new ReminderRow(Sample.Events()[0], Sample.Now, DateOnly.FromDateTime(Sample.Now.Date), archivedView: false, showDate: true);
        Assert.False(plain.IsRepeating);
        Assert.DoesNotContain(plain.Details, line => line.Label == "Repeats");
    }

    [Fact]
    public void TheWindowWordsTheRuleFromTheSyncedSeries() => ui.Run(() =>
    {
        var bridge = new FakeBridge([.. Sample.Events(), Occurrence("t1", "2026-10-02"), Occurrence("t2", "2026-10-09")]) { Series = [Trash()] };
        var window = UiFixture.Open(Sample.Store(events: []), bridge: bridge);
        try
        {
            Assert.True(window.SyncAsync(showActivity: false).IsCompletedSuccessfully);
            UiFixture.Settle();
            var row = window.ReminderList.Items.OfType<ReminderRow>().First(shown => shown.Event.Id == "t1");
            Assert.True(row.IsRepeating);
            Assert.Contains(new DetailLine("Repeats", "Every week on Friday at 8:00 AM"), row.Details);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void DeletingThisOneDeletesOnlyThatOccurrence() => ui.Run(() =>
    {
        var bridge = new FakeBridge([Occurrence("t1", "2026-10-02"), Occurrence("t2", "2026-10-09")]) { Series = [Trash()] };
        var window = UiFixture.Open(Sample.Store(events: []), bridge: bridge);
        try
        {
            Assert.True(window.SyncAsync(showActivity: false).IsCompletedSuccessfully);
            UiFixture.Settle();
            CalendarEvent? asked = null;
            window.AskSeriesScope = item =>
            {
                asked = item;
                return SeriesScope.ThisOne;
            };
            var first = window.ReminderList.Items.OfType<ReminderRow>().First(row => row.Event.Id == "t1").Event;
            Assert.True(window.RequestDeleteAsync(first).IsCompletedSuccessfully);
            UiFixture.Settle();
            Assert.Equal("t1", asked?.Id);
            Assert.Contains("delete t1", bridge.Calls);
            Assert.Equal(["t2"], window.ReminderList.Items.OfType<ReminderRow>().Select(row => row.Event.Id));
        }
        finally { window.Close(); }
    });

    [Fact]
    public void DeletingAllFutureDeletesFromThatDate() => ui.Run(() =>
    {
        var bridge = new FakeBridge([Occurrence("t1", "2026-10-02"), Occurrence("t2", "2026-10-09"), Occurrence("t3", "2026-10-16")]) { Series = [Trash()] };
        var window = UiFixture.Open(Sample.Store(events: []), bridge: bridge);
        try
        {
            Assert.True(window.SyncAsync(showActivity: false).IsCompletedSuccessfully);
            UiFixture.Settle();
            window.AskSeriesScope = _ => SeriesScope.AllFuture;
            var second = window.ReminderList.Items.OfType<ReminderRow>().First(row => row.Event.Id == "t2").Event;
            Assert.True(window.RequestDeleteAsync(second).IsCompletedSuccessfully);
            UiFixture.Settle();
            Assert.Contains("delete series trash from 2026-10-09", bridge.Calls);
            Assert.Equal(["t1"], window.ReminderList.Items.OfType<ReminderRow>().Select(row => row.Event.Id));
            Assert.Equal("Deleted: Trash pickup and the ones after it", window.ActionBarText.Text);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void CancellingTheChoiceDeletesNothing() => ui.Run(() =>
    {
        var bridge = new FakeBridge([Occurrence("t1", "2026-10-02")]) { Series = [Trash()] };
        var window = UiFixture.Open(Sample.Store(events: []), bridge: bridge);
        try
        {
            Assert.True(window.SyncAsync(showActivity: false).IsCompletedSuccessfully);
            UiFixture.Settle();
            window.AskSeriesScope = _ => null;
            var only = window.ReminderList.Items.OfType<ReminderRow>().Single().Event;
            Assert.True(window.RequestDeleteAsync(only).IsCompletedSuccessfully);
            Assert.DoesNotContain(bridge.Calls, call => call.StartsWith("delete", StringComparison.Ordinal));
        }
        finally { window.Close(); }
    });

    [Theory]
    [MemberData(nameof(RenderTests.Palettes), MemberType = typeof(RenderTests))]
    public void Pictures(string palette, bool dark) => ui.Run(() =>
    {
        XpTheme.Apply(dark);
        var store = Sample.Store([.. Sample.Events(), Occurrence("t1", "2026-10-02"), Occurrence("t2", "2026-10-09")]);
        store.SaveSeries([Trash()]);
        var window = UiFixture.Open(store);
        try
        {
            window.ShowSection("life");
            window.OpenReminder("t1");
            ui.Save(window, $"{palette}-main-repeat");
        }
        finally { window.Close(); }

        var box = new XpSeriesScopeBox("Delete Repeating Reminder", "\"Trash pickup\" is a repeating reminder.", "Delete");
        UiFixture.Show(box);
        try { ui.Save(box, $"{palette}-series-scope"); }
        finally { box.Close(); }
    });

    private static ReminderSeries Trash() => new()
    {
        Id = "trash",
        Title = "Trash pickup",
        TimeZone = "America/Denver",
        Rule = Rule("weekly", weekdays: [5], start: "2026-10-02"),
    };

    private static CalendarEvent Occurrence(string id, string date)
    {
        var day = DateOnly.ParseExact(date, "yyyy-MM-dd");
        var item = Sample.Plain(id, "Trash pickup", Sample.Local(day.Year, day.Month, day.Day, 8, 0), offsets: 900);
        item.Category = "housing";
        item.Domain = "life";
        item.SeriesId = "trash";
        item.OccurrenceDate = date;
        return item;
    }
}
