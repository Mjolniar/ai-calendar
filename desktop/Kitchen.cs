using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace AICalendar.Desktop;

// The household's shopping list, pantry and weekly meal plan (docs\kitchen.md). All kitchen
// rules live on the server; the app shows GET /v1/kitchen and sends small edits. What is here is
// reading that answer, and the few display rules: tonight from the local date, the list's three
// parts, pantry sections, and **bold**.

/// <summary>Status values of a shopping-list item, as the bridge sends them.</summary>
public static class ShoppingStatus
{
    public const string Need = "need";
    public const string Ordered = "ordered";
    public const string Got = "got";
}

/// <summary>Status values of a pantry item.</summary>
public static class PantryStatus
{
    public const string Have = "have";
    public const string Low = "low";
    public const string Out = "out";
}

/// <summary>One line of the shopping list.</summary>
public sealed record ShoppingItem
{
    public required string Id { get; init; }
    public required string Name { get; init; }
    public string Qty { get; init; } = "";
    public string Note { get; init; } = "";
    /// <summary><see cref="ShoppingStatus"/>: need, ordered or got.</summary>
    public string Status { get; init; } = ShoppingStatus.Need;
    /// <summary>Set by Claude when the item is on a placed order, e.g. "Walmart #2000..., Tue 11/3".</summary>
    public string OrderRef { get; init; } = "";
    /// <summary>device, ai or pantry.</summary>
    public string Source { get; init; } = "device";
    public string AddedBy { get; init; } = "";
    public DateTimeOffset? AddedAt { get; init; }
    public DateTimeOffset? UpdatedAt { get; init; }
    /// <summary>When it became got; null otherwise.</summary>
    public DateTimeOffset? DoneAt { get; init; }

    public bool IsOpen => Status is ShoppingStatus.Need or ShoppingStatus.Ordered;
}

/// <summary>One thing kept in the house, and whether Claude thinks there is any (bridge 0.10.0: an estimate).</summary>
public sealed record PantryItem
{
    public required string Id { get; init; }
    public required string Name { get; init; }
    public string Section { get; init; } = "Other";
    /// <summary><see cref="PantryStatus"/>: have, low or out. Claude's estimate.</summary>
    public string Status { get; init; } = PantryStatus.Have;
    public string Note { get; init; } = "";
    /// <summary>Why Claude thinks so: "bought 10/26", "used in Wed jambalaya". "" when not said.</summary>
    public string Basis { get; init; } = "";
    /// <summary>When Claude last assessed it; null from an older bridge.</summary>
    public DateTimeOffset? CheckedAt { get; init; }
    public DateTimeOffset? UpdatedAt { get; init; }
}

/// <summary>Kinds of a correction to the pantry estimate.</summary>
public static class CorrectionKind
{
    public const string Out = "out";
    public const string Have = "have";
    public const string Note = "note";
}

/// <summary>Where a correction stands.</summary>
public static class CorrectionStatus
{
    public const string Open = "open";
    public const string Applied = "applied";
    public const string Dismissed = "dismissed";
}

/// <summary>
/// Something a person told Claude about the pantry. "out" and "have" are applied at once; a note
/// stays open until Claude answers it.
/// </summary>
public sealed record KitchenCorrection
{
    public required string Id { get; init; }
    /// <summary><see cref="CorrectionKind"/>: out, have or note.</summary>
    public required string Kind { get; init; }
    public string Text { get; init; } = "";
    public string? ItemId { get; init; }
    public string ItemName { get; init; } = "";
    /// <summary><see cref="CorrectionStatus"/>: open, applied or dismissed.</summary>
    public string Status { get; init; } = CorrectionStatus.Open;
    public string Reply { get; init; } = "";
    public string AddedBy { get; init; } = "";
    public DateTimeOffset? AddedAt { get; init; }
    public DateTimeOffset? ResolvedAt { get; init; }

    public bool IsOpen => Status == CorrectionStatus.Open;
}

/// <summary>How a dinner went, keyed by its date. Empty strings are "not said".</summary>
public sealed record DinnerNote
{
    /// <summary>"yes", "skipped" or "".</summary>
    public string Cooked { get; init; } = "";
    /// <summary>"loved", "ok", "no" or "".</summary>
    public string Rating { get; init; } = "";
    public string Note { get; init; } = "";
    public DateTimeOffset? UpdatedAt { get; init; }

    public static readonly DinnerNote Empty = new();
}

/// <summary>One dinner of a meal-plan week.</summary>
public sealed record MealDay
{
    public required DateOnly Date { get; init; }
    public string Label { get; init; } = "";
    public required string Dinner { get; init; }
    public string Base { get; init; } = "";
    public string Note { get; init; } = "";
    /// <summary>cook, leftovers or special.</summary>
    public string Kind { get; init; } = "cook";
    /// <summary>The id of a recipe in the same week; null on leftovers nights.</summary>
    public string? RecipeId { get; init; }

    public bool IsLeftovers => string.Equals(Kind, "leftovers", StringComparison.OrdinalIgnoreCase);
}

public sealed record Recipe
{
    public required string Id { get; init; }
    public string Title { get; init; } = "";
    public string Sub { get; init; } = "";
    public string Ingredients { get; init; } = "";
    public IReadOnlyList<string> Steps { get; init; } = [];
    public string Note { get; init; } = "";
}

/// <summary>A list of extras with the week, e.g. "Breakfast &amp; lunch".</summary>
public sealed record MealBox(string Title, IReadOnlyList<string> Items);

/// <summary>A published meal-plan week, Tuesday to the next Monday. Fields the apps do not use are ignored.</summary>
public sealed record MealPlan
{
    public DateOnly? Start { get; init; }
    public DateOnly? End { get; init; }
    public string Title { get; init; } = "";
    /// <summary>draft, approved or ordered.</summary>
    public string Status { get; init; } = "";
    public string Tagline { get; init; } = "";
    public IReadOnlyList<string> Meta { get; init; } = [];
    public IReadOnlyList<MealDay> Days { get; init; } = [];
    public IReadOnlyList<MealBox> Boxes { get; init; } = [];
    public IReadOnlyList<Recipe> Recipes { get; init; } = [];

    /// <summary>The day's recipe; null for a leftovers night or a recipe id the week does not have.</summary>
    public Recipe? RecipeFor(MealDay day) =>
        day.RecipeId is { Length: > 0 } id ? Recipes.FirstOrDefault(recipe => recipe.Id == id) : null;
}

/// <summary>What GET /v1/kitchen answers.</summary>
public sealed class KitchenSnapshot
{
    public long Revision { get; set; }
    /// <summary>The household's date that the server picked <see cref="Week"/> and <see cref="NextWeek"/> by (bridge 0.11.0); null from an older bridge.</summary>
    public DateOnly? Today { get; set; }
    public List<ShoppingItem> List { get; set; } = [];
    public List<PantryItem> Pantry { get; set; } = [];
    public MealPlan? Week { get; set; }
    public MealPlan? NextWeek { get; set; }
    public Dictionary<string, DinnerNote> Notes { get; set; } = new(StringComparer.Ordinal);
    /// <summary>Corrections to the pantry estimate: open ones, then those handled in the last 7 days. Empty from an older bridge.</summary>
    public List<KitchenCorrection> Corrections { get; set; } = [];
    /// <summary>Records left out because they could not be read.</summary>
    public int Skipped { get; set; }

    public DinnerNote NoteFor(DateOnly date) =>
        Notes.TryGetValue(KitchenText.DateKey(date), out var note) ? note : DinnerNote.Empty;

    /// <summary>A copy whose lists can be changed without touching this one (the records themselves never change).</summary>
    public KitchenSnapshot Copy() => new()
    {
        Revision = Revision,
        Today = Today,
        List = [.. List],
        Pantry = [.. Pantry],
        Week = Week,
        NextWeek = NextWeek,
        Notes = new Dictionary<string, DinnerNote>(Notes, StringComparer.Ordinal),
        Corrections = [.. Corrections],
        Skipped = Skipped,
    };
}

/// <summary>Answer to adding or changing a list item.</summary>
public sealed record ShoppingItemResult(long Revision, ShoppingItem Item, bool Created);

/// <summary>
/// Answer to a correction: the stored correction, the pantry item it changed (out and have), the
/// list item that "out" added (rule 2), and the pantry-added list item that "have" took off the list
/// (bridge 0.11.0). <paramref name="Repeated"/>: the same clientId was sent before, so nothing changed
/// and this is the correction stored then.
/// </summary>
public sealed record CorrectionResult(long Revision, KitchenCorrection Correction, PantryItem? Item, ShoppingItem? ListItem,
    string? RemovedListItemId = null, bool Repeated = false);

public sealed record DinnerNoteResult(long Revision, string Date, DinnerNote Note);

/// <summary>
/// How Undo puts back a line it removed: as it was, to buy, on order with its order, or bought
/// (bridge 0.11.0; an older bridge adds it to buy).
/// </summary>
public sealed record ShoppingItemRestore(string Status, string OrderRef);

/// <summary>Fields to change on a list item; null fields are not sent.</summary>
public sealed record ShoppingItemChange(string? Name = null, string? Qty = null, string? Note = null, string? Status = null);

/// <summary>Fields to change on a dinner note; null fields are not sent.</summary>
public sealed record DinnerNoteChange(string? Cooked = null, string? Rating = null, string? Note = null);

/// <summary>
/// Reads the kitchen answers. Optional fields may be missing; a record that cannot be read (not an
/// object, no id or name, a status the app does not know) is skipped and counted, never fatal. A
/// snapshot without its list and pantry arrays is an error, never "an empty kitchen".
/// </summary>
public static partial class KitchenJson
{
    [GeneratedRegex("^[A-Za-z0-9_-]{1,64}$")]
    private static partial Regex IdPattern();

    [GeneratedRegex(@"^\d{4}-\d{2}-\d{2}$")]
    private static partial Regex DatePattern();

    public static bool IsValidId(string? id) => id is not null && IdPattern().IsMatch(id);

    public static bool IsValidDate(string? date) => date is not null && DatePattern().IsMatch(date);

    public static KitchenSnapshot ReadSnapshot(JsonElement root)
    {
        if (root.ValueKind != JsonValueKind.Object) throw new FormatException("The kitchen answer is not an object.");
        if (!root.TryGetProperty("list", out var list) || list.ValueKind != JsonValueKind.Array)
            throw new FormatException("The kitchen answer has no shopping list.");
        if (!root.TryGetProperty("pantry", out var pantry) || pantry.ValueKind != JsonValueKind.Array)
            throw new FormatException("The kitchen answer has no pantry.");
        var snapshot = new KitchenSnapshot { Revision = Revision(root) };
        var skipped = 0;
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var element in list.EnumerateArray())
        {
            if (ReadItem(element) is { } item && seen.Add(item.Id)) snapshot.List.Add(item);
            else skipped++;
        }
        seen.Clear();
        foreach (var element in pantry.EnumerateArray())
        {
            if (ReadPantryItem(element) is { } item && seen.Add(item.Id)) snapshot.Pantry.Add(item);
            else skipped++;
        }
        snapshot.Today = Date(Text(root, "today"));
        snapshot.Week = root.TryGetProperty("week", out var week) ? ReadPlan(week, ref skipped) : null;
        snapshot.NextWeek = root.TryGetProperty("nextWeek", out var next) ? ReadPlan(next, ref skipped) : null;
        if (root.TryGetProperty("corrections", out var corrections) && corrections.ValueKind == JsonValueKind.Array)
        {
            seen.Clear();
            foreach (var element in corrections.EnumerateArray())
            {
                if (ReadCorrection(element) is { } correction && seen.Add(correction.Id)) snapshot.Corrections.Add(correction);
                else skipped++;
            }
        }
        if (root.TryGetProperty("notes", out var notes) && notes.ValueKind == JsonValueKind.Object)
        {
            foreach (var entry in notes.EnumerateObject())
            {
                if (IsValidDate(entry.Name) && ReadNote(entry.Value) is { } note) snapshot.Notes[entry.Name] = note;
                else skipped++;
            }
        }
        snapshot.Skipped = skipped;
        return snapshot;
    }

    public static ShoppingItem? ReadItem(JsonElement element)
    {
        if (element.ValueKind != JsonValueKind.Object) return null;
        var id = Text(element, "id");
        var name = Text(element, "name").Trim();
        var status = Text(element, "status").Trim().ToLowerInvariant();
        if (!IsValidId(id) || name.Length == 0) return null;
        if (status is not (ShoppingStatus.Need or ShoppingStatus.Ordered or ShoppingStatus.Got)) return null;
        return new ShoppingItem
        {
            Id = id,
            Name = name,
            Qty = Text(element, "qty").Trim(),
            Note = Text(element, "note").Trim(),
            Status = status,
            OrderRef = Text(element, "orderRef").Trim(),
            Source = Text(element, "source").Trim() is { Length: > 0 } source ? source : "device",
            AddedBy = Text(element, "addedBy").Trim(),
            AddedAt = Time(element, "addedAt"),
            UpdatedAt = Time(element, "updatedAt"),
            DoneAt = Time(element, "doneAt"),
        };
    }

    public static PantryItem? ReadPantryItem(JsonElement element)
    {
        if (element.ValueKind != JsonValueKind.Object) return null;
        var id = Text(element, "id");
        var name = Text(element, "name").Trim();
        var status = Text(element, "status").Trim().ToLowerInvariant();
        if (!IsValidId(id) || name.Length == 0) return null;
        if (status is not (PantryStatus.Have or PantryStatus.Low or PantryStatus.Out)) return null;
        return new PantryItem
        {
            Id = id,
            Name = name,
            Section = Text(element, "section").Trim() is { Length: > 0 } section ? section : "Other",
            Status = status,
            Note = Text(element, "note").Trim(),
            Basis = Text(element, "basis").Trim(),
            CheckedAt = Time(element, "checkedAt"),
            UpdatedAt = Time(element, "updatedAt"),
        };
    }

    /// <summary>A correction, or null when it has no usable id or a kind or status the app does not know.</summary>
    public static KitchenCorrection? ReadCorrection(JsonElement element)
    {
        if (element.ValueKind != JsonValueKind.Object) return null;
        var id = Text(element, "id");
        var kind = Text(element, "kind").Trim().ToLowerInvariant();
        var status = Text(element, "status").Trim().ToLowerInvariant();
        if (!IsValidId(id)) return null;
        if (kind is not (CorrectionKind.Out or CorrectionKind.Have or CorrectionKind.Note)) return null;
        if (status is not (CorrectionStatus.Open or CorrectionStatus.Applied or CorrectionStatus.Dismissed)) return null;
        var itemId = Text(element, "itemId").Trim();
        return new KitchenCorrection
        {
            Id = id,
            Kind = kind,
            Text = Text(element, "text").Trim(),
            ItemId = itemId.Length > 0 ? itemId : null,
            ItemName = Text(element, "itemName").Trim(),
            Status = status,
            Reply = Text(element, "reply").Trim(),
            AddedBy = Text(element, "addedBy").Trim(),
            AddedAt = Time(element, "addedAt"),
            ResolvedAt = Time(element, "resolvedAt"),
        };
    }

    public static DinnerNote? ReadNote(JsonElement element)
    {
        if (element.ValueKind != JsonValueKind.Object) return null;
        var cooked = Text(element, "cooked").Trim().ToLowerInvariant();
        var rating = Text(element, "rating").Trim().ToLowerInvariant();
        return new DinnerNote
        {
            Cooked = cooked is "yes" or "skipped" ? cooked : "",
            Rating = rating is "loved" or "ok" or "no" ? rating : "",
            Note = Text(element, "note"),
            UpdatedAt = Time(element, "updatedAt"),
        };
    }

    /// <summary>A week, or null for null or anything that is not an object. Unreadable days and recipes are skipped.</summary>
    public static MealPlan? ReadPlan(JsonElement element, ref int skipped)
    {
        if (element.ValueKind != JsonValueKind.Object) return null;
        DateOnly? start = null, end = null;
        var title = "";
        if (element.TryGetProperty("week", out var week) && week.ValueKind == JsonValueKind.Object)
        {
            start = Date(Text(week, "start"));
            end = Date(Text(week, "end"));
            title = Text(week, "title").Trim();
        }
        var days = new List<MealDay>();
        foreach (var entry in Array(element, "days"))
        {
            if (ReadDay(entry) is { } day) days.Add(day);
            else skipped++;
        }
        var recipes = new List<Recipe>();
        foreach (var entry in Array(element, "recipes"))
        {
            if (ReadRecipe(entry) is { } recipe) recipes.Add(recipe);
            else skipped++;
        }
        var boxes = new List<MealBox>();
        foreach (var entry in Array(element, "boxes"))
        {
            if (entry.ValueKind != JsonValueKind.Object) { skipped++; continue; }
            var items = Strings(entry, "items");
            var boxTitle = Text(entry, "title").Trim();
            if (boxTitle.Length > 0 || items.Count > 0) boxes.Add(new MealBox(boxTitle, items));
        }
        return new MealPlan
        {
            Start = start,
            End = end,
            Title = title,
            Status = Text(element, "status").Trim().ToLowerInvariant(),
            Tagline = Text(element, "tagline").Trim(),
            Meta = Strings(element, "meta"),
            Days = [.. days.OrderBy(day => day.Date)],
            Boxes = boxes,
            Recipes = recipes,
        };
    }

    private static MealDay? ReadDay(JsonElement element)
    {
        if (element.ValueKind != JsonValueKind.Object) return null;
        var date = Date(Text(element, "date"));
        var dinner = Text(element, "dinner").Trim();
        if (date is null || dinner.Length == 0) return null;
        var recipe = Text(element, "recipe").Trim();
        return new MealDay
        {
            Date = date.Value,
            Label = Text(element, "label").Trim(),
            Dinner = dinner,
            Base = Text(element, "base").Trim(),
            Note = Text(element, "note").Trim(),
            Kind = Text(element, "kind").Trim() is { Length: > 0 } kind ? kind.ToLowerInvariant() : "cook",
            RecipeId = recipe.Length > 0 ? recipe : null,
        };
    }

    private static Recipe? ReadRecipe(JsonElement element)
    {
        if (element.ValueKind != JsonValueKind.Object) return null;
        var id = Text(element, "id").Trim();
        if (id.Length == 0) return null;
        // Ingredients are one string; a list of them is taken too.
        var ingredients = element.TryGetProperty("ingredients", out var value) && value.ValueKind == JsonValueKind.Array
            ? string.Join(", ", Strings(element, "ingredients"))
            : Text(element, "ingredients").Trim();
        return new Recipe
        {
            Id = id,
            Title = Text(element, "title").Trim(),
            Sub = Text(element, "sub").Trim(),
            Ingredients = ingredients,
            Steps = Strings(element, "steps"),
            Note = Text(element, "note").Trim(),
        };
    }

    public static ShoppingItemResult ReadItemResult(JsonElement root, bool createdByStatus)
    {
        var item = root.ValueKind == JsonValueKind.Object && root.TryGetProperty("item", out var value) ? ReadItem(value) : null;
        if (item is null) throw new FormatException("The calendar service sent back no readable list item.");
        return new ShoppingItemResult(Revision(root), item, Bool(root, "created") ?? createdByStatus);
    }

    public static CorrectionResult ReadCorrectionResult(JsonElement root)
    {
        var correction = root.ValueKind == JsonValueKind.Object && root.TryGetProperty("correction", out var value) ? ReadCorrection(value) : null;
        if (correction is null) throw new FormatException("The calendar service sent back no readable correction.");
        var item = root.TryGetProperty("item", out var changed) ? ReadPantryItem(changed) : null;
        var listItem = root.TryGetProperty("listItem", out var added) ? ReadItem(added) : null;
        var removed = Text(root, "removedListItemId");
        return new CorrectionResult(Revision(root), correction, item, listItem,
            IsValidId(removed) ? removed : null, Bool(root, "repeated") ?? false);
    }

    public static DinnerNoteResult ReadNoteResult(JsonElement root, string date)
    {
        var note = root.ValueKind == JsonValueKind.Object && root.TryGetProperty("note", out var value) ? ReadNote(value) : null;
        if (note is null) throw new FormatException("The calendar service sent back no readable dinner note.");
        var answered = Text(root, "date");
        return new DinnerNoteResult(Revision(root), IsValidDate(answered) ? answered : date, note);
    }

    public static long Revision(JsonElement root)
    {
        if (root.ValueKind != JsonValueKind.Object || !root.TryGetProperty("revision", out var value)) return 0;
        if (value.ValueKind == JsonValueKind.Number)
        {
            if (value.TryGetInt64(out var whole)) return whole;
            if (value.TryGetDouble(out var real) && real is >= long.MinValue and <= long.MaxValue) return (long)real;
        }
        return value.ValueKind == JsonValueKind.String && long.TryParse(value.GetString(), NumberStyles.Integer, CultureInfo.InvariantCulture, out var text) ? text : 0;
    }

    // ------------------------------------------------------------ request bodies

    /// <summary>
    /// The body of POST /v1/kitchen/items: the name, and qty and note when given. Undo also sends
    /// restore with the status and order the line had (an older bridge ignores them).
    /// </summary>
    public static Dictionary<string, object> AddItemBody(string name, string? qty, string? note, ShoppingItemRestore? restore = null) =>
        restore is null
            ? Fields(("name", name), ("qty", qty), ("note", note))
            : Fields(("name", name), ("qty", qty), ("note", note), ("restore", true), ("status", restore.Status), ("orderRef", restore.OrderRef));

    public static Dictionary<string, object> Body(ShoppingItemChange change) =>
        Fields(("name", change.Name), ("qty", change.Qty), ("note", change.Note), ("status", change.Status));

    /// <summary>
    /// The body of POST /v1/kitchen/corrections: the kind, and the text, the item and the client id
    /// when given. The same client id sent again is not stored twice (bridge 0.11.0).
    /// </summary>
    public static Dictionary<string, object> CorrectionBody(string kind, string? text, string? itemId, string? clientId = null) =>
        Fields(("kind", kind), ("text", text), ("itemId", itemId), ("clientId", clientId));

    public static Dictionary<string, object> Body(DinnerNoteChange change) =>
        Fields(("cooked", change.Cooked), ("rating", change.Rating), ("note", change.Note));

    /// <summary>A new id for a correction, so that one sent twice is stored once.</summary>
    public static string NewClientId() => Guid.NewGuid().ToString("N");

    private static Dictionary<string, object> Fields(params (string Name, object? Value)[] fields)
    {
        var body = new Dictionary<string, object>(StringComparer.Ordinal);
        foreach (var (name, value) in fields)
        {
            if (value is not null) body[name] = value;
        }
        return body;
    }

    // ------------------------------------------------------------ helpers

    private static string Text(JsonElement element, string name) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString() ?? ""
            : "";

    private static bool? Bool(JsonElement element, string name) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value) && value.ValueKind is JsonValueKind.True or JsonValueKind.False
            ? value.GetBoolean()
            : null;

    private static DateTimeOffset? Time(JsonElement element, string name) =>
        DateTimeOffset.TryParse(Text(element, name), CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var time) ? time : null;

    private static DateOnly? Date(string text) =>
        IsValidDate(text) && DateOnly.TryParseExact(text, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var date) ? date : null;

    private static IEnumerable<JsonElement> Array(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Array ? value.EnumerateArray() : [];

    /// <summary>A list of strings: an array (other entries left out), or a single string as a list of one.</summary>
    private static IReadOnlyList<string> Strings(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out var value)) return [];
        if (value.ValueKind == JsonValueKind.String)
            return value.GetString() is { Length: > 0 } single && single.Trim().Length > 0 ? [single.Trim()] : [];
        if (value.ValueKind != JsonValueKind.Array) return [];
        return value.EnumerateArray()
            .Where(entry => entry.ValueKind == JsonValueKind.String)
            .Select(entry => (entry.GetString() ?? "").Trim())
            .Where(entry => entry.Length > 0)
            .ToList();
    }
}

/// <summary>The kitchen's display rules: what tonight is, the list's three parts, pantry sections, bold text, wording.</summary>
public static class KitchenText
{
    /// <summary>The "Got it" part keeps what was bought in the last seven days.</summary>
    public static readonly TimeSpan GotWindow = TimeSpan.FromDays(7);

    /// <summary>Today on this PC's calendar: the local date, not the UTC one.</summary>
    public static DateOnly LocalToday(DateTimeOffset now) => DateOnly.FromDateTime(now.LocalDateTime);

    public static string DateKey(DateOnly date) => date.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);

    /// <summary>Today's dinner and its week: the published day whose date is today, in this week or the next.</summary>
    public static (MealPlan Plan, MealDay Day)? Tonight(KitchenSnapshot snapshot, DateOnly today)
    {
        foreach (var plan in new[] { snapshot.Week, snapshot.NextWeek })
        {
            if (plan?.Days.FirstOrDefault(day => day.Date == today) is { } day) return (plan, day);
        }
        return null;
    }

    /// <summary>
    /// The open part of the list (need, oldest first, then ordered, oldest first) and the "Got it"
    /// part: what was bought in the last seven days, newest first. Equal times keep the server's order.
    /// </summary>
    public static (IReadOnlyList<ShoppingItem> Need, IReadOnlyList<ShoppingItem> Ordered, IReadOnlyList<ShoppingItem> Got) Arrange(
        IEnumerable<ShoppingItem> items, DateTimeOffset now)
    {
        var all = items.ToList();
        var need = all.Where(item => item.Status == ShoppingStatus.Need).OrderBy(item => item.AddedAt ?? DateTimeOffset.MaxValue).ToList();
        var ordered = all.Where(item => item.Status == ShoppingStatus.Ordered).OrderBy(item => item.AddedAt ?? DateTimeOffset.MaxValue).ToList();
        var cutoff = now - GotWindow;
        var got = all
            .Where(item => item.Status == ShoppingStatus.Got && (GotAt(item) is not { } at || at >= cutoff))
            .OrderByDescending(item => GotAt(item) ?? DateTimeOffset.MinValue)
            .ToList();
        return (need, ordered, got);
    }

    private static DateTimeOffset? GotAt(ShoppingItem item) => item.DoneAt ?? item.UpdatedAt;

    /// <summary>Pantry items by section, sections in the order they first appear (the server sorts them).</summary>
    public static IReadOnlyList<(string Section, IReadOnlyList<PantryItem> Items)> Sections(IEnumerable<PantryItem> items) =>
        items.GroupBy(item => item.Section, StringComparer.OrdinalIgnoreCase)
            .Select(group => (group.First().Section, (IReadOnlyList<PantryItem>)group.ToList()))
            .ToList();

    /// <summary>The estimate in plain words: Have, Running low, Probably out.</summary>
    public static string EstimateLabel(string status) => status switch
    {
        PantryStatus.Out => "Probably out",
        PantryStatus.Low => "Running low",
        _ => "Have",
    };

    /// <summary>
    /// The On hand list: sections in the server's order, and in each what is probably out, then
    /// what is running low, then the rest, each in the server's order. With
    /// <paramref name="lowAndOutOnly"/>, only those, and only sections that have any.
    /// </summary>
    public static IReadOnlyList<(string Section, IReadOnlyList<PantryItem> Items)> OnHand(IEnumerable<PantryItem> items, bool lowAndOutOnly = false) =>
        Sections(items)
            .Select(section => (section.Section, (IReadOnlyList<PantryItem>)section.Items
                .Where(item => !lowAndOutOnly || item.Status != PantryStatus.Have)
                .OrderBy(item => item.Status switch { PantryStatus.Out => 0, PantryStatus.Low => 1, _ => 2 })
                .ToList()))
            .Where(section => section.Item2.Count > 0)
            .ToList();

    /// <summary>"What Claude thinks you have. Updated Mon 10/26." from the newest checkedAt, local date.</summary>
    public static string OnHandHeader(IEnumerable<PantryItem> items)
    {
        var newest = items.Select(item => item.CheckedAt).Where(at => at is not null).Max();
        return newest is { } at
            ? $"What Claude thinks you have. Updated {at.LocalDateTime.ToString("ddd M/d", CultureInfo.InvariantCulture)}."
            : "What Claude thinks you have.";
    }

    /// <summary>Corrections show as handled for this long.</summary>
    public static readonly TimeSpan HandledWindow = TimeSpan.FromDays(7);

    /// <summary>Collapsed, the corrections list shows this many.</summary>
    public const int CorrectionsShown = 3;

    /// <summary>
    /// Open corrections oldest first, then those handled in the last 7 days newest first. Collapsed,
    /// the newest <see cref="CorrectionsShown"/> of them (by when they were made or answered), in the same order.
    /// As on the phone, an open one without a time comes last and counts as the newest; equal times
    /// keep the server's order.
    /// </summary>
    public static IReadOnlyList<KitchenCorrection> ArrangeCorrections(IEnumerable<KitchenCorrection> corrections, DateTimeOffset now, bool all)
    {
        var list = corrections.ToList();
        var open = list.Where(item => item.IsOpen).OrderBy(item => item.AddedAt ?? DateTimeOffset.MaxValue);
        var cutoff = now - HandledWindow;
        var handled = list
            .Where(item => !item.IsOpen && (Handled(item) is not { } at || at >= cutoff))
            .OrderByDescending(item => Handled(item) ?? DateTimeOffset.MinValue);
        var arranged = open.Concat(handled).ToList();
        if (all || arranged.Count <= CorrectionsShown) return arranged;
        var newest = arranged.OrderByDescending(Latest).Take(CorrectionsShown).ToHashSet();
        return arranged.Where(newest.Contains).ToList();
    }

    private static DateTimeOffset? Handled(KitchenCorrection item) => item.ResolvedAt ?? item.AddedAt;

    private static DateTimeOffset Latest(KitchenCorrection item) => item.ResolvedAt ?? item.AddedAt ?? DateTimeOffset.MaxValue;

    /// <summary>What the person said: the note, or "We're out: Eggs".</summary>
    public static string CorrectionSaid(KitchenCorrection item)
    {
        var name = item.ItemName.Length > 0 ? item.ItemName : "an item";
        return item.Kind switch
        {
            CorrectionKind.Out => $"We're out: {name}",
            CorrectionKind.Have => $"We have it: {name}",
            _ => item.Text,
        };
    }

    /// <summary>
    /// "Waiting for Claude"; "Claude: reply" for a handled note; "Applied: reply" for out and have.
    /// Without a reply, worded as on the phone: "Claude: Left as it was." for a dismissed note, "Done." otherwise.
    /// </summary>
    public static string CorrectionAnswer(KitchenCorrection item)
    {
        if (item.IsOpen) return "Waiting for Claude";
        if (item.Kind != CorrectionKind.Note) return $"Applied: {(item.Reply.Length > 0 ? item.Reply : "Done.")}";
        return $"Claude: {(item.Reply.Length > 0 ? item.Reply : item.Status == CorrectionStatus.Dismissed ? "Left as it was." : "Done.")}";
    }

    /// <summary>
    /// Splits text with **bold** parts into runs. An opening ** without a closing one is kept as
    /// written; empty bold parts are dropped.
    /// </summary>
    public static IReadOnlyList<(string Text, bool Bold)> Segments(string? text)
    {
        var segments = new List<(string, bool)>();
        if (string.IsNullOrEmpty(text)) return segments;
        var index = 0;
        while (index < text.Length)
        {
            var open = text.IndexOf("**", index, StringComparison.Ordinal);
            if (open < 0) break;
            var close = text.IndexOf("**", open + 2, StringComparison.Ordinal);
            if (close < 0) break;
            if (open > index) segments.Add((text[index..open], false));
            if (close > open + 2) segments.Add((text[(open + 2)..close], true));
            index = close + 2;
        }
        if (index < text.Length) segments.Add((text[index..], false));
        return segments;
    }

    /// <summary>The text without its ** markers, for places that cannot show bold.</summary>
    public static string StripBold(string? text) => string.Concat(Segments(text).Select(segment => segment.Text));

    /// <summary>"Cooked · Loved it", "Skipped", or "" when nothing was said.</summary>
    public static string HowItWent(DinnerNote note)
    {
        var parts = new List<string>();
        if (CookedLabel(note.Cooked) is { } cooked) parts.Add(cooked);
        if (RatingLabel(note.Rating) is { } rating) parts.Add(rating);
        return string.Join(" · ", parts);
    }

    public static string? CookedLabel(string cooked) => cooked switch
    {
        "yes" => "Cooked",
        "skipped" => "Skipped",
        _ => null,
    };

    public static string? RatingLabel(string rating) => rating switch
    {
        "loved" => "Loved it",
        "ok" => "It was OK",
        "no" => "Not again",
        _ => null,
    };

    /// <summary>"Oct 27 – Nov 2", or "" without both dates.</summary>
    public static string Range(DateOnly? start, DateOnly? end) =>
        start is { } from && end is { } to
            ? $"{from.ToString("MMM d", CultureInfo.InvariantCulture)} – {to.ToString("MMM d", CultureInfo.InvariantCulture)}"
            : "";

    /// <summary>The second line of a list item: quantity, note, where it stands and who added it.</summary>
    public static string ItemDetail(ShoppingItem item, DateOnly today)
    {
        var parts = new List<string>();
        if (item.Qty.Length > 0) parts.Add(item.Qty);
        if (item.Note.Length > 0) parts.Add(item.Note);
        switch (item.Status)
        {
            case ShoppingStatus.Ordered:
                parts.Add(item.OrderRef.Length > 0 ? $"Ordered: {item.OrderRef}" : "Ordered");
                break;
            case ShoppingStatus.Got:
                parts.Add(item.DoneAt is { } done ? $"Got {DayWord(DateOnly.FromDateTime(done.LocalDateTime), today)}" : "Got");
                break;
            default:
                if (item.Source == "pantry") parts.Add("Out in the pantry");
                else if (item.AddedBy.Length > 0) parts.Add($"Added by {item.AddedBy}");
                break;
        }
        return string.Join(" · ", parts);
    }

    /// <summary>"today", "yesterday", or "Tue 10/27".</summary>
    public static string DayWord(DateOnly date, DateOnly today) =>
        date == today ? "today"
        : date == today.AddDays(-1) ? "yesterday"
        : date.ToString("ddd M/d", CultureInfo.InvariantCulture);

    /// <summary>
    /// A name as the server compares names (rule 1), a copy of normalizeName in bridge\src\kitchen.ts:
    /// accents dropped (NFKD, combining marks removed), lower case, only letters and digits of any
    /// script, a plural "s" dropped from each word longer than three letters that does not end in
    /// "ss", the words run together. A name with no letter or digit is itself, trimmed and in lower
    /// case. The app sends names as typed; the tests' stand-in for the server merges names with this.
    /// </summary>
    public static string NameKey(string name)
    {
        var bare = new StringBuilder(name.Length);
        foreach (var rune in name.Normalize(NormalizationForm.FormKD).EnumerateRunes())
        {
            if (Rune.GetUnicodeCategory(rune) is not (UnicodeCategory.NonSpacingMark or UnicodeCategory.SpacingCombiningMark or UnicodeCategory.EnclosingMark))
                bare.Append(rune.ToString());
        }
        var words = new List<string>();
        var word = new StringBuilder();
        foreach (var rune in bare.ToString().ToLowerInvariant().EnumerateRunes())
        {
            if (Rune.IsLetter(rune) || Rune.IsNumber(rune)) word.Append(rune.ToString());
            else if (word.Length > 0)
            {
                words.Add(word.ToString());
                word.Clear();
            }
        }
        if (word.Length > 0) words.Add(word.ToString());
        var key = string.Concat(words.Select(part =>
            part.Length > 3 && part.EndsWith('s') && !part.EndsWith("ss", StringComparison.Ordinal) ? part[..^1] : part));
        return key.Length > 0 ? key : name.Trim().ToLowerInvariant();
    }
}
