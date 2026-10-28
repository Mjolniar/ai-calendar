using System.Text.Json;

namespace AICalendar.Desktop.Tests;

/// <summary>Reading GET /v1/kitchen and the edit answers, and the kitchen's display rules. No window.</summary>
public sealed class KitchenRulesTests
{
    private static JsonElement Parse(string json) => JsonDocument.Parse(json).RootElement;

    // ------------------------------------------------------------ the parser

    [Fact]
    public void ReadsTheWholeSnapshot()
    {
        var kitchen = KitchenSample.Snapshot();
        Assert.Equal(41, kitchen.Revision);
        Assert.Equal(new DateOnly(2026, 10, 29), kitchen.Today);
        Assert.Equal(0, kitchen.Skipped);
        Assert.Equal(7, kitchen.List.Count);
        Assert.Equal(9, kitchen.Pantry.Count);

        var salmon = kitchen.List.Single(item => item.Id == "ksalmon00004");
        Assert.Equal("Salmon fillets", salmon.Name);
        Assert.Equal("1 lb", salmon.Qty);
        Assert.Equal(ShoppingStatus.Ordered, salmon.Status);
        Assert.Equal("Walmart #200000000001, Mon 11/2", salmon.OrderRef);
        Assert.Equal("ai", salmon.Source);
        Assert.Equal("Claude", salmon.AddedBy);
        Assert.Equal(new DateTimeOffset(2026, 10, 26, 12, 0, 0, TimeSpan.Zero), salmon.AddedAt);
        Assert.Null(salmon.DoneAt);
        Assert.Equal(new DateTimeOffset(2026, 10, 27, 18, 0, 0, TimeSpan.Zero), kitchen.List.Single(item => item.Name == "Milk").DoneAt);

        var turkey = kitchen.Pantry.Single(item => item.Id == "pturkey00004");
        Assert.Equal(("Freezer", PantryStatus.Low, "1 lb packs"), (turkey.Section, turkey.Status, turkey.Note));
        Assert.Equal("1 lb left after Thu chili", turkey.Basis);
        Assert.Equal(new DateTimeOffset(2026, 10, 26, 18, 0, 0, TimeSpan.Zero), turkey.CheckedAt);
        // An item from before the estimate (no basis, no checkedAt) still reads.
        var cumin = kitchen.Pantry.Single(item => item.Name == "Cumin");
        Assert.Equal(("", (DateTimeOffset?)null), (cumin.Basis, cumin.CheckedAt));

        Assert.Equal(6, kitchen.Corrections.Count);
        var beans = kitchen.Corrections[0];
        Assert.Equal(("cnote0000001", CorrectionKind.Note, "Black beans: 2 cans left", "pbeans000001", "Black beans", CorrectionStatus.Open),
            (beans.Id, beans.Kind, beans.Text, beans.ItemId, beans.ItemName, beans.Status));
        Assert.Null(beans.ResolvedAt);
        Assert.Null(kitchen.Corrections[1].ItemId);
        var oil = kitchen.Corrections[2];
        Assert.Equal((CorrectionKind.Out, CorrectionStatus.Applied, "Marked out and added to the shopping list."), (oil.Kind, oil.Status, oil.Reply));
        Assert.Equal(new DateTimeOffset(2026, 10, 27, 18, 0, 0, TimeSpan.Zero), oil.ResolvedAt);

        var week = Assert.IsType<MealPlan>(kitchen.Week);
        Assert.Equal(new DateOnly(2026, 10, 27), week.Start);
        Assert.Equal(new DateOnly(2026, 11, 2), week.End);
        Assert.Equal("Meal Plan · Oct 27–Nov 2, 2026", week.Title);
        Assert.Equal("ordered", week.Status);
        Assert.Equal("For 2 · quick weeknights · make-ahead lunches", week.Tagline);
        Assert.Equal(["Ordered · delivery **Mon 4–6pm** · no substitutions"], week.Meta);
        // Sorted by date, whatever order they were published in.
        Assert.Equal(Enumerable.Range(0, 7).Select(offset => new DateOnly(2026, 10, 27).AddDays(offset)), week.Days.Select(day => day.Date));
        var thursday = week.Days[2];
        Assert.Equal(("Turkey chili", "cornbread muffins", "chili"), (thursday.Dinner, thursday.Base, thursday.RecipeId));
        Assert.Equal("Start it by **5:30** so it simmers an hour", thursday.Note);
        var chili = Assert.IsType<Recipe>(week.RecipeFor(thursday));
        Assert.Equal(5, chili.Steps.Count);
        Assert.Equal("Thu · 1 hr 15 min · serves 4 (leftovers Friday)", chili.Sub);
        Assert.True(week.Days[3].IsLeftovers);
        Assert.Null(week.RecipeFor(week.Days[3]));
        Assert.Equal("Breakfast & lunch", Assert.Single(week.Boxes).Title);
        Assert.Equal(3, week.Boxes[0].Items.Count);

        var next = Assert.IsType<MealPlan>(kitchen.NextWeek);
        Assert.Equal("draft", next.Status);
        Assert.Equal(2, next.Days.Count);

        Assert.Equal(new DinnerNote { Cooked = "yes", Rating = "loved", Note = "Less dill next time", UpdatedAt = new DateTimeOffset(2026, 10, 28, 3, 0, 0, TimeSpan.Zero) },
            kitchen.NoteFor(new DateOnly(2026, 10, 27)));
        Assert.Equal("skipped", kitchen.NoteFor(new DateOnly(2026, 10, 28)).Cooked);
        Assert.Same(DinnerNote.Empty, kitchen.NoteFor(KitchenSample.Today));
    }

    [Fact]
    public void MissingOptionalFieldsGetTheirDefaults()
    {
        var kitchen = KitchenSample.Read("""
        {
          "list": [ { "id": "k1", "name": "  Foil ", "status": "need" } ],
          "pantry": [ { "id": "p1", "name": "Rice", "status": "have" }, { "id": "p2", "name": "Beans", "status": "low", "section": "  " } ],
          "week": {
            "days": [ { "date": "2026-10-27", "dinner": "Soup" } ],
            "recipes": [ { "id": "soup", "ingredients": ["broth", "carrots"], "steps": "Simmer it all." }, { "id": "bare" } ]
          }
        }
        """);
        Assert.Equal(0, kitchen.Revision);
        // A bridge before 0.11.0 sends no date of its own.
        Assert.Null(kitchen.Today);
        var foil = Assert.Single(kitchen.List);
        Assert.Equal("Foil", foil.Name);
        Assert.Equal(("", "", "", "device", ""), (foil.Qty, foil.Note, foil.OrderRef, foil.Source, foil.AddedBy));
        Assert.Null(foil.AddedAt);
        Assert.Null(foil.DoneAt);
        Assert.All(kitchen.Pantry, item => Assert.Equal("Other", item.Section));
        Assert.Equal("", kitchen.Pantry[0].Note);
        Assert.Equal("", kitchen.Pantry[0].Basis);
        Assert.Null(kitchen.Pantry[0].CheckedAt);

        var week = Assert.IsType<MealPlan>(kitchen.Week);
        Assert.Null(week.Start);
        Assert.Equal("", week.Title);
        Assert.Empty(week.Meta);
        Assert.Empty(week.Boxes);
        var day = Assert.Single(week.Days);
        Assert.Equal(("", "", "", "cook", (string?)null), (day.Label, day.Base, day.Note, day.Kind, day.RecipeId));
        Assert.Equal("broth, carrots", week.Recipes[0].Ingredients);
        Assert.Equal(["Simmer it all."], week.Recipes[0].Steps);
        Assert.Empty(week.Recipes[1].Steps);
        Assert.Null(kitchen.NextWeek);
        Assert.Empty(kitchen.Notes);
        // A bridge before 0.10.0 sends no corrections: none, not an error.
        Assert.Empty(kitchen.Corrections);
    }

    [Fact]
    public void AMalformedRecordIsSkippedAndCounted()
    {
        var kitchen = KitchenSample.Read("""
        {
          "revision": 7,
          "list": [
            12,
            { "name": "No id", "status": "need" },
            { "id": "bad id", "name": "Space in the id", "status": "need" },
            { "id": "k2", "name": "   ", "status": "need" },
            { "id": "k3", "name": "Lost", "status": "lost" },
            { "id": "k4", "name": "Kept", "status": "ORDERED", "qty": 2 },
            { "id": "k4", "name": "Same id again", "status": "need" }
          ],
          "pantry": [ { "id": "p1", "name": "Gone", "status": "gone" }, { "id": "p2", "name": "Rice", "status": "out" } ],
          "week": { "days": [ { "date": "10/27/2026", "dinner": "Bad date" }, { "date": "2026-10-28" }, { "date": "2026-10-29", "dinner": "Kept" } ] },
          "nextWeek": "not a week",
          "notes": { "tomorrow": { "cooked": "yes" }, "2026-10-29": { "cooked": "maybe", "rating": "loved" }, "2026-10-30": [] }
        }
        """);
        var kept = Assert.Single(kitchen.List);
        Assert.Equal(("k4", ShoppingStatus.Ordered, ""), (kept.Id, kept.Status, kept.Qty));
        Assert.Equal("Rice", Assert.Single(kitchen.Pantry).Name);
        Assert.Equal("Kept", Assert.Single(kitchen.Week!.Days).Dinner);
        Assert.Null(kitchen.NextWeek);
        // An unknown cooked value reads as "not said"; the rest of the note is kept.
        Assert.Equal(("", "loved"), (kitchen.Notes["2026-10-29"].Cooked, kitchen.Notes["2026-10-29"].Rating));
        // Six list items, one pantry item, two days and two notes.
        Assert.Equal(11, kitchen.Skipped);
    }

    [Theory]
    [InlineData("[]")]
    [InlineData("""{ "revision": 3, "pantry": [] }""")]
    [InlineData("""{ "list": {}, "pantry": [] }""")]
    [InlineData("""{ "list": [], "pantry": null }""")]
    public void AnAnswerWithoutTheListAndPantryIsAnErrorNotAnEmptyKitchen(string json) =>
        Assert.Throws<FormatException>(() => KitchenSample.Read(json));

    [Fact]
    public void ReadsTheAnswersToEdits()
    {
        var added = KitchenJson.ReadItemResult(Parse("""{ "revision": 9, "item": { "id": "k1", "name": "Foil", "status": "need" }, "created": true }"""), createdByStatus: false);
        Assert.Equal((9L, "k1", true), (added.Revision, added.Item.Id, added.Created));
        // Without "created", the status code says it (201 or 200).
        Assert.True(KitchenJson.ReadItemResult(Parse("""{ "revision": 9, "item": { "id": "k1", "name": "Foil", "status": "need" } }"""), createdByStatus: true).Created);
        Assert.Throws<FormatException>(() => KitchenJson.ReadItemResult(Parse("""{ "revision": 9, "item": { "id": "k1" } }"""), false));

        var outResult = KitchenJson.ReadCorrectionResult(Parse("""
            { "revision": 10,
              "correction": { "id": "c1", "kind": "out", "text": "", "itemId": "p1", "itemName": "Rice", "status": "applied",
                              "reply": "Marked out and added to the shopping list.", "addedBy": "PC", "addedAt": "2026-10-29T23:30:00Z",
                              "resolvedAt": "2026-10-29T23:30:00Z" },
              "item": { "id": "p1", "name": "Rice", "section": "Dry goods", "status": "out", "basis": "PC said it's out" },
              "listItem": { "id": "k9", "name": "Rice", "status": "need", "source": "pantry", "addedBy": "Pantry" } }
            """));
        Assert.Equal((10L, "c1", CorrectionStatus.Applied), (outResult.Revision, outResult.Correction.Id, outResult.Correction.Status));
        Assert.Equal(("Rice", PantryStatus.Out, "PC said it's out", "k9"), (outResult.Item?.Name, outResult.Item?.Status, outResult.Item?.Basis, outResult.ListItem?.Id));
        Assert.Null(outResult.RemovedListItemId);
        Assert.False(outResult.Repeated);
        // "We have it" took the pantry's line off the list; a repeat says so (bridge 0.11.0).
        var haveResult = KitchenJson.ReadCorrectionResult(Parse("""
            { "revision": 12, "repeated": true, "removedListItemId": "k9",
              "correction": { "id": "c3", "kind": "have", "itemId": "p1", "itemName": "Rice", "status": "applied", "reply": "Marked as on hand." },
              "item": { "id": "p1", "name": "Rice", "status": "have" } }
            """));
        Assert.Equal(("k9", true, PantryStatus.Have), (haveResult.RemovedListItemId, haveResult.Repeated, haveResult.Item?.Status));
        Assert.Null(KitchenJson.ReadCorrectionResult(Parse("""
            { "revision": 12, "removedListItemId": "not an id", "correction": { "id": "c3", "kind": "have", "status": "applied" } }
            """)).RemovedListItemId);
        // A note changes nothing yet: no item, no list item.
        var noteResult = KitchenJson.ReadCorrectionResult(Parse("""
            { "revision": 11, "correction": { "id": "c2", "kind": "note", "text": "Tossed the cilantro", "status": "open", "reply": "", "addedBy": "PC" } }
            """));
        Assert.Equal(("Tossed the cilantro", true), (noteResult.Correction.Text, noteResult.Correction.IsOpen));
        Assert.Null(noteResult.Item);
        Assert.Null(noteResult.ListItem);
        Assert.Throws<FormatException>(() => KitchenJson.ReadCorrectionResult(Parse("""{ "revision": 11, "correction": { "id": "c2", "kind": "wish", "status": "open" } }""")));

        var note = KitchenJson.ReadNoteResult(Parse("""{ "revision": 11, "date": "2026-10-29", "note": { "cooked": "yes", "rating": "", "note": "" } }"""), "2026-10-29");
        Assert.Equal(("2026-10-29", "yes", 11L), (note.Date, note.Note.Cooked, note.Revision));
    }

    [Theory]
    [InlineData("""{ "revision": 42 }""", 42)]
    [InlineData("""{ "revision": 42.0 }""", 42)]
    [InlineData("""{ "revision": "42" }""", 42)]
    [InlineData("""{ "revision": null }""", 0)]
    [InlineData("""{ }""", 0)]
    public void ReadsTheRevision(string json, long expected) => Assert.Equal(expected, KitchenJson.Revision(Parse(json)));

    [Fact]
    public void AnEditSendsOnlyWhatChanges()
    {
        static string Json(Dictionary<string, object> body) => JsonSerializer.Serialize(body);
        Assert.Equal("""{"status":"got"}""", Json(KitchenJson.Body(new ShoppingItemChange(Status: ShoppingStatus.Got))));
        Assert.Equal("""{"name":"Foil"}""", Json(KitchenJson.AddItemBody("Foil", null, null)));
        Assert.Equal("""{"name":"Milk","qty":"1 gal","note":""}""", Json(KitchenJson.AddItemBody("Milk", "1 gal", "")));
        Assert.Equal("""{"kind":"out","itemId":"p1"}""", Json(KitchenJson.CorrectionBody(CorrectionKind.Out, null, "p1")));
        Assert.Equal("""{"kind":"note","text":"Tossed the cilantro"}""", Json(KitchenJson.CorrectionBody(CorrectionKind.Note, "Tossed the cilantro", null)));
        // A client id, so a correction sent twice is stored once (bridge 0.11.0; 0.10.0 ignores it).
        Assert.Equal("""{"kind":"have","itemId":"p1","clientId":"c0ffee"}""", Json(KitchenJson.CorrectionBody(CorrectionKind.Have, null, "p1", "c0ffee")));
        Assert.Matches("^[0-9a-f]{32}$", KitchenJson.NewClientId());
        Assert.NotEqual(KitchenJson.NewClientId(), KitchenJson.NewClientId());
        // Undo puts a line back as it was: restore is a JSON true, with its status and order.
        Assert.Equal("""{"name":"Salmon","qty":"1 lb","restore":true,"status":"ordered","orderRef":"Walmart #2, Mon 11/2"}""",
            Json(KitchenJson.AddItemBody("Salmon", "1 lb", null, new ShoppingItemRestore(ShoppingStatus.Ordered, "Walmart #2, Mon 11/2"))));
        Assert.Equal("""{"name":"Milk","restore":true,"status":"got","orderRef":""}""",
            Json(KitchenJson.AddItemBody("Milk", null, null, new ShoppingItemRestore(ShoppingStatus.Got, ""))));
        // "" is sent: it clears the rating.
        Assert.Equal("""{"rating":""}""", Json(KitchenJson.Body(new DinnerNoteChange(Rating: ""))));
        Assert.Equal("""{"cooked":"yes","note":"Good"}""", Json(KitchenJson.Body(new DinnerNoteChange(Cooked: "yes", Note: "Good"))));
    }

    [Theory]
    [InlineData("kfoil0000001", true)]
    [InlineData("p_1-a", true)]
    [InlineData("", false)]
    [InlineData("../v1/sync", false)]
    [InlineData("k 1", false)]
    public void KitchenIdsAreCheckedBeforeTheyGoInAPath(string id, bool valid)
    {
        Assert.Equal(valid, KitchenJson.IsValidId(id));
        if (valid) Assert.Equal(id, BridgeClient.KitchenId(id));
        else Assert.Throws<ArgumentException>(() => BridgeClient.KitchenId(id));
    }

    // ------------------------------------------------------------ tonight

    [Theory]
    [InlineData(0, 30)]
    [InlineData(23, 30)]
    public void TodayIsTheLocalDateNotTheUtcOne(int hour, int minute)
    {
        // Just after midnight and just before it, local time: one of the two is another day in UTC
        // wherever the tests run (except in UTC itself), and both are October 29 here.
        var now = Sample.Local(2026, 10, 29, hour, minute);
        Assert.Equal(new DateOnly(2026, 10, 29), KitchenText.LocalToday(now));
        var tonight = KitchenText.Tonight(KitchenSample.Snapshot(), KitchenText.LocalToday(now));
        Assert.Equal("Turkey chili", tonight?.Day.Dinner);
    }

    [Theory]
    [InlineData("2026-10-27", "Oven-baked salmon", "this")]
    [InlineData("2026-11-02", "Leftovers night", "this")]
    [InlineData("2026-11-03", "Turkey meatballs", "next")]
    [InlineData("2026-10-26", null, null)]
    [InlineData("2026-11-05", null, null)]
    public void TonightIsTheDayOfTodayInThisWeekOrTheNext(string date, string? dinner, string? week)
    {
        var kitchen = KitchenSample.Snapshot();
        var tonight = KitchenText.Tonight(kitchen, DateOnly.Parse(date));
        Assert.Equal(dinner, tonight?.Day.Dinner);
        if (week is not null) Assert.Same(week == "this" ? kitchen.Week : kitchen.NextWeek, tonight?.Plan);
    }

    // ------------------------------------------------------------ the list and the pantry

    [Fact]
    public void TheListIsNeedThenOrderedThenWhatWasBoughtThisWeek()
    {
        var (need, ordered, got) = KitchenText.Arrange(KitchenSample.Snapshot().List, KitchenSample.Now);
        Assert.Equal(["Aluminum foil", "Olive oil", "Paper towels"], need.Select(item => item.Name));
        Assert.Equal(["Salmon fillets", "Sweet potatoes"], ordered.Select(item => item.Name));
        // Eggs were bought nine days ago: past the seven days.
        Assert.Equal(["Milk"], got.Select(item => item.Name));
    }

    [Fact]
    public void GotItKeepsSevenDaysNewestFirst()
    {
        var now = new DateTimeOffset(2026, 10, 29, 12, 0, 0, TimeSpan.Zero);
        ShoppingItem Got(string id, DateTimeOffset? done, DateTimeOffset? updated = null) =>
            new() { Id = id, Name = id, Status = ShoppingStatus.Got, DoneAt = done, UpdatedAt = updated };
        var (_, _, got) = KitchenText.Arrange(
        [
            Got("week-old", now.AddDays(-7)),
            Got("too-old", now.AddDays(-7).AddMinutes(-1)),
            Got("today", now.AddHours(-1)),
            Got("by-update", null, now.AddDays(-2)),
            Got("no-time", null),
        ], now);
        Assert.Equal(["today", "by-update", "week-old", "no-time"], got.Select(item => item.Id));
    }

    [Fact]
    public void PantrySectionsKeepTheServersOrder()
    {
        var sections = KitchenText.Sections(KitchenSample.Snapshot().Pantry);
        Assert.Equal(["Canned & jarred", "Freezer", "Fridge & counter", "Sauces & condiments", "Spices"], sections.Select(section => section.Section));
        Assert.Equal(["Frozen broccoli", "Ground turkey"], sections[1].Items.Select(item => item.Name));

        // A section spelled differently further down joins the first.
        PantryItem Item(string id, string section) => new() { Id = id, Name = id, Section = section };
        var merged = KitchenText.Sections([Item("a", "Freezer"), Item("b", "Spices"), Item("c", "freezer")]);
        Assert.Equal(["Freezer", "Spices"], merged.Select(section => section.Section));
        Assert.Equal(["a", "c"], merged[0].Items.Select(item => item.Id));
    }

    [Fact]
    public void AnUnreadableCorrectionIsSkippedAndCounted()
    {
        var kitchen = KitchenSample.Read("""
        {
          "list": [], "pantry": [],
          "corrections": [
            "text",
            { "kind": "note", "text": "No id", "status": "open" },
            { "id": "c1", "kind": "wish", "text": "Unknown kind", "status": "open" },
            { "id": "c2", "kind": "note", "text": "Unknown status", "status": "pending" },
            { "id": "c3", "kind": "NOTE", "text": "Kept", "status": "Open" },
            { "id": "c3", "kind": "note", "text": "Same id again", "status": "open" }
          ]
        }
        """);
        var kept = Assert.Single(kitchen.Corrections);
        Assert.Equal(("c3", CorrectionKind.Note, CorrectionStatus.Open, "", ""), (kept.Id, kept.Kind, kept.Status, kept.Reply, kept.AddedBy));
        Assert.Null(kept.AddedAt);
        Assert.Equal(5, kitchen.Skipped);
        // Not an array: ignored, as an older bridge's answer would be.
        Assert.Empty(KitchenSample.Read("""{ "list": [], "pantry": [], "corrections": {} }""").Corrections);
    }

    // ------------------------------------------------------------ on hand

    [Fact]
    public void OnHandPutsProbablyOutAndRunningLowFirstInEachSection()
    {
        var sections = KitchenText.OnHand(KitchenSample.Snapshot().Pantry);
        Assert.Equal(["Canned & jarred", "Freezer", "Fridge & counter", "Sauces & condiments", "Spices"], sections.Select(section => section.Section));
        Assert.Equal(["Diced tomatoes", "Black beans"], sections[0].Items.Select(item => item.Name));
        Assert.Equal(["Olive oil", "Eggs"], sections[2].Items.Select(item => item.Name));

        PantryItem Item(string name, string status) => new() { Id = name, Name = name, Section = "Fridge", Status = status };
        var mixed = KitchenText.OnHand([Item("a", PantryStatus.Have), Item("b", PantryStatus.Low), Item("c", PantryStatus.Out), Item("d", PantryStatus.Low)]);
        Assert.Equal(["c", "b", "d", "a"], mixed[0].Items.Select(item => item.Name));

        // Running low and out only: Have rows go, and so do sections left empty.
        var lowAndOut = KitchenText.OnHand(KitchenSample.Snapshot().Pantry, lowAndOutOnly: true);
        Assert.Equal(["Canned & jarred", "Freezer", "Fridge & counter", "Sauces & condiments"], lowAndOut.Select(section => section.Section));
        Assert.Equal(["Diced tomatoes", "Ground turkey", "Olive oil", "Salsa"], lowAndOut.SelectMany(section => section.Items).Select(item => item.Name));
    }

    [Fact]
    public void TheEstimateIsSaidInPlainWords()
    {
        Assert.Equal("Have", KitchenText.EstimateLabel(PantryStatus.Have));
        Assert.Equal("Running low", KitchenText.EstimateLabel(PantryStatus.Low));
        Assert.Equal("Probably out", KitchenText.EstimateLabel(PantryStatus.Out));
        // Updated: the newest checkedAt, as a local date.
        Assert.Equal("What Claude thinks you have. Updated Mon 10/26.", KitchenText.OnHandHeader(KitchenSample.Snapshot().Pantry));
        Assert.Equal("What Claude thinks you have.", KitchenText.OnHandHeader([new PantryItem { Id = "p", Name = "Rice" }]));
        Assert.Equal("What Claude thinks you have.", KitchenText.OnHandHeader([]));
    }

    [Fact]
    public void CorrectionsAreOpenOnesThenAWeekOfHandledOnesNewestFirst()
    {
        var corrections = KitchenSample.Snapshot().Corrections;
        // The Eggs one was handled eleven days ago: past the week.
        Assert.Equal(["cnote0000001", "cnote0000002", "cout00000003", "cnote0000004", "cnote0000005"],
            KitchenText.ArrangeCorrections(corrections, KitchenSample.Now, all: true).Select(item => item.Id));
        // Collapsed: the newest three, still in that order.
        Assert.Equal(["cnote0000001", "cnote0000002", "cout00000003"],
            KitchenText.ArrangeCorrections(corrections, KitchenSample.Now, all: false).Select(item => item.Id));
        // Four waiting: the newest three of them.
        KitchenCorrection Open(string id, int day) => new() { Id = id, Kind = CorrectionKind.Note, Text = id, AddedAt = new DateTimeOffset(2026, 10, day, 12, 0, 0, TimeSpan.Zero) };
        Assert.Equal(["b", "c", "d"], KitchenText.ArrangeCorrections([Open("a", 20), Open("b", 21), Open("c", 22), Open("d", 23)], KitchenSample.Now, all: false).Select(item => item.Id));

        // As on the phone: an open one without a time comes after the others, and counts as the
        // newest when collapsed. Equal times keep the server's order.
        KitchenCorrection Untimed(string id) => new() { Id = id, Kind = CorrectionKind.Note, Text = id };
        Assert.Equal(["a", "c", "x", "b"], KitchenText.ArrangeCorrections([Untimed("x"), Open("a", 20), Open("c", 22), Untimed("b")], KitchenSample.Now, all: true).Select(item => item.Id));
        Assert.Equal(["c", "x", "b"], KitchenText.ArrangeCorrections([Untimed("x"), Open("a", 20), Open("c", 22), Untimed("b")], KitchenSample.Now, all: false).Select(item => item.Id));
        Assert.Equal(["b", "a"], KitchenText.ArrangeCorrections([Open("b", 21), Open("a", 21)], KitchenSample.Now, all: true).Select(item => item.Id));
    }

    [Fact]
    public void ACorrectionSaysWhatWasSaidAndWhatCameOfIt()
    {
        var corrections = KitchenSample.Snapshot().Corrections.ToDictionary(item => item.Id);
        Assert.Equal("Black beans: 2 cans left", KitchenText.CorrectionSaid(corrections["cnote0000001"]));
        Assert.Equal("Waiting for Claude", KitchenText.CorrectionAnswer(corrections["cnote0000001"]));
        Assert.Equal("We're out: Olive oil", KitchenText.CorrectionSaid(corrections["cout00000003"]));
        Assert.Equal("Applied: Marked out and added to the shopping list.", KitchenText.CorrectionAnswer(corrections["cout00000003"]));
        Assert.Equal("Claude: Added **Brown rice** to Dry goods as Have.", KitchenText.CorrectionAnswer(corrections["cnote0000004"]));
        Assert.Equal("Claude: None that I know of. Say if you want it on Monday's order.", KitchenText.CorrectionAnswer(corrections["cnote0000005"]));
        Assert.Equal("We have it: Eggs", KitchenText.CorrectionSaid(corrections["cold00000006"]));
        // Without a reply, worded as on the phone.
        Assert.Equal("Claude: Left as it was.", KitchenText.CorrectionAnswer(new KitchenCorrection { Id = "x", Kind = CorrectionKind.Note, Status = CorrectionStatus.Dismissed }));
        Assert.Equal("Claude: Done.", KitchenText.CorrectionAnswer(new KitchenCorrection { Id = "x", Kind = CorrectionKind.Note, Status = CorrectionStatus.Applied }));
        Assert.Equal("Applied: Done.", KitchenText.CorrectionAnswer(new KitchenCorrection { Id = "x", Kind = CorrectionKind.Out, Status = CorrectionStatus.Dismissed }));
        Assert.Equal("Applied: Done.", KitchenText.CorrectionAnswer(new KitchenCorrection { Id = "x", Kind = CorrectionKind.Have, Status = CorrectionStatus.Applied }));
    }

    // ------------------------------------------------------------ bold

    [Fact]
    public void BoldPartsBecomeTheirOwnRuns()
    {
        Assert.Equal(
            [("Ordered · delivery ", false), ("Mon 4–6pm", true), (" · no substitutions", false)],
            KitchenText.Segments("Ordered · delivery **Mon 4–6pm** · no substitutions"));
        Assert.Equal([("all", true)], KitchenText.Segments("**all**"));
        Assert.Equal([("a ", false), ("b", true), (" c ", false), ("d", true)], KitchenText.Segments("a **b** c **d**"));
        Assert.Equal([("plain", false)], KitchenText.Segments("plain"));
        Assert.Empty(KitchenText.Segments(""));
        Assert.Empty(KitchenText.Segments(null));
        // An unclosed ** is kept as written; an empty pair is dropped.
        Assert.Equal([("a **b", false)], KitchenText.Segments("a **b"));
        Assert.Equal([("x", true), (" and **y", false)], KitchenText.Segments("**x** and **y"));
        Assert.Empty(KitchenText.Segments("****"));
    }

    [Theory]
    [InlineData("Ordered · delivery **Mon 4–6pm** · no substitutions", "Ordered · delivery Mon 4–6pm · no substitutions")]
    [InlineData("**Note:** less dill", "Note: less dill")]
    [InlineData("a **b", "a **b")]
    [InlineData("2 * 3 = 6", "2 * 3 = 6")]
    public void StrippingBoldLeavesTheText(string text, string expected) => Assert.Equal(expected, KitchenText.StripBold(text));

    // ------------------------------------------------------------ wording

    [Fact]
    public void Wording()
    {
        Assert.Equal("Cooked · Loved it", KitchenText.HowItWent(new DinnerNote { Cooked = "yes", Rating = "loved" }));
        Assert.Equal("Skipped", KitchenText.HowItWent(new DinnerNote { Cooked = "skipped" }));
        Assert.Equal("Not again", KitchenText.HowItWent(new DinnerNote { Rating = "no" }));
        Assert.Equal("", KitchenText.HowItWent(DinnerNote.Empty));
        Assert.Equal("Oct 27 – Nov 2", KitchenText.Range(new DateOnly(2026, 10, 27), new DateOnly(2026, 11, 2)));
        Assert.Equal("", KitchenText.Range(null, new DateOnly(2026, 11, 2)));

        var list = KitchenSample.Snapshot().List.ToDictionary(item => item.Name);
        var today = KitchenSample.Today;
        Assert.Equal("Added by Pixel 10 Pro XL", KitchenText.ItemDetail(list["Aluminum foil"], today));
        Assert.Equal("1 bottle · Out in the pantry", KitchenText.ItemDetail(list["Olive oil"], today));
        Assert.Equal("the big pack · Added by Claude", KitchenText.ItemDetail(list["Paper towels"], today));
        Assert.Equal("1 lb · Ordered: Walmart #200000000001, Mon 11/2", KitchenText.ItemDetail(list["Salmon fillets"], today));
        Assert.Equal("1 gal · Got Tue 10/27", KitchenText.ItemDetail(list["Milk"], today));
        Assert.Equal("yesterday", KitchenText.DayWord(today.AddDays(-1), today));
        Assert.Equal(KitchenText.NameKey("Aluminum foil"), KitchenText.NameKey(" aluminum-FOIL! "));
    }

    // The server's rule 1 (normalizeName in bridge\src\kitchen.ts), which the stand-in for it uses.
    [Theory]
    [InlineData("Aluminum foil", "aluminumfoil")]
    [InlineData(" aluminum-FOIL! ", "aluminumfoil")]
    [InlineData("Paper towels", "papertowel")]
    [InlineData("Eggs", "egg")]
    [InlineData("Gas", "gas")]
    [InlineData("Glass", "glass")]
    [InlineData("Jalapeños", "jalapeno")]
    [InlineData("Crème fraîche", "cremefraiche")]
    [InlineData("½ & ½", "1212")]
    [InlineData("Молоко", "молоко")]
    [InlineData("牛奶", "牛奶")]
    [InlineData("Chips & Salsas", "chipsalsa")]
    [InlineData("  !!!  ", "!!!")]
    [InlineData("", "")]
    public void NamesAreComparedAsTheServerComparesThem(string name, string key) => Assert.Equal(key, KitchenText.NameKey(name));
}
