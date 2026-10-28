using System.Globalization;
using System.Net;
using System.Net.Http;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using AICalendar.Desktop.Xp;

namespace AICalendar.Desktop;

/// <summary>What the Kitchen tab needs from the window around it.</summary>
internal interface IKitchenHost
{
    BridgeClient Client { get; }
    bool IsPaired { get; }
    DateTimeOffset Now { get; }
    CancellationToken Lifetime { get; }
    void SetStatus(string text);
    void ShowActionBar(string text, Func<Task>? undo);
    /// <summary>An edit the server did not take. The window says so in a message box.</summary>
    void ReportKitchenProblem(string heading, string detail);
    void Log(string message);
    /// <summary>The kitchen was loaded or changed; the status bar's count may differ.</summary>
    void KitchenChanged();
    void KitchenPartChanged(string part);
}

/// <summary>
/// The Kitchen tab: tonight and this week's meal plan, the shopping list, and the pantry. A thin
/// view of GET /v1/kitchen (docs\kitchen.md). Every edit goes straight to the server; the
/// answer is applied and the kitchen fetched again. A failed edit changes nothing on screen.
/// </summary>
public partial class KitchenView : UserControl
{
    internal const string WeekPartName = "week";
    internal const string ListPartName = "list";
    internal const string PantryPartName = "pantry";

    internal const string OfflineMessage = "Can't reach the cloud calendar. Kitchen needs a connection.";

    internal const string EmptyPantryMessage = "Claude fills this in after the next grocery order.";

    private readonly HashSet<string> _busy = [];
    /// <summary>Dinner notes typed and not saved yet, by day; a switch to another day keeps them.</summary>
    private readonly Dictionary<DateOnly, string> _noteDrafts = [];
    private KitchenSnapshot? _snapshot;
    private string _part = WeekPartName;
    private bool _loading;
    private bool _reloadRequested;
    private bool _failureLogged;
    private bool _binding;
    private DateTimeOffset? _loadedAt;
    /// <summary>Counts fetches started and edits applied, so an answer can tell whether an edit came after its fetch began.</summary>
    private long _sequence;
    private long _lastEdit;
    /// <summary>The local date the tab was last drawn for.</summary>
    private DateOnly? _renderedDay;
    private DateOnly? _selectedDate;
    private DateOnly? _noteDate;
    private string _noteBaseline = "";
    private string? _selectedPantryId;
    private bool _lowAndOutOnly;
    private bool _allCorrections;
    private string? _tellItemId;
    private string _tellItemName = "";
    /// <summary>The Tell Claude note last sent without an answer, and its client id: sent again, it is stored once.</summary>
    private (string Text, string? ItemId, string ClientId)? _unsentNote;

    public KitchenView()
    {
        InitializeComponent();
    }

    internal IKitchenHost? Host { get; set; }

    /// <summary>What was last loaded, with this PC's own changes applied; null before the first load.</summary>
    internal KitchenSnapshot? Snapshot => _snapshot;

    /// <summary>Why the last load failed; null when it worked.</summary>
    internal string? LoadProblem { get; private set; }

    /// <summary>The last edit that failed, as "heading: reason".</summary>
    internal string? LastProblem { get; private set; }

    internal string Part => _part;

    internal DateOnly? SelectedDate => _selectedDate;

    /// <summary>When the kitchen was last asked for, whatever asked; null before the first time.</summary>
    internal DateTimeOffset? FetchedAt { get; private set; }

    /// <summary>The On hand row whose correction buttons show; null when none is.</summary>
    internal string? SelectedPantryId => _selectedPantryId;

    /// <summary>The pantry item the Tell Claude note is about (from Something else...); null for a plain note.</summary>
    internal string? TellItemId => _tellItemId;

    /// <summary>"5 on the list" for the status bar; "" before the first load.</summary>
    internal string CountText
    {
        get
        {
            if (_snapshot is null) return "";
            var open = _snapshot.List.Count(item => item.IsOpen);
            return open == 1 ? "1 on the list" : $"{open} on the list";
        }
    }

    private MealDayRow? SelectedDay => DayList.SelectedItem as MealDayRow;

    // ------------------------------------------------------------ bold text

    /// <summary>Text with **bold** parts, for a TextBlock in a template.</summary>
    public static readonly DependencyProperty RichTextProperty = DependencyProperty.RegisterAttached(
        "RichText", typeof(string), typeof(KitchenView),
        new PropertyMetadata(null, (target, args) =>
        {
            if (target is TextBlock block) SetRich(block, args.NewValue as string);
        }));

    public static string? GetRichText(DependencyObject element) => (string?)element.GetValue(RichTextProperty);

    public static void SetRichText(DependencyObject element, string? value) => element.SetValue(RichTextProperty, value);

    /// <summary>Fills a TextBlock with the text, its **bold** parts in bold and its line breaks kept.</summary>
    internal static void SetRich(TextBlock block, string? text)
    {
        block.Inlines.Clear();
        foreach (var (part, bold) in KitchenText.Segments(text))
        {
            var lines = part.Split('\n');
            for (var index = 0; index < lines.Length; index++)
            {
                if (index > 0) block.Inlines.Add(new LineBreak());
                var line = lines[index].TrimEnd('\r');
                if (line.Length == 0) continue;
                var run = new Run(line);
                block.Inlines.Add(bold ? new Bold(run) : run);
            }
        }
    }

    // ------------------------------------------------------------ loading

    /// <summary>
    /// Fetches the kitchen. The answer is kept, and drawn when it can read differently: another
    /// revision, or another day (this PC's date, or the server's date and the weeks it picked by
    /// it), so a tab left open overnight moves on to the new day. Otherwise it is not drawn again,
    /// so a refresh keeps the selection, the scroll position and a note being typed.
    /// One fetch is out at a time; asked for meanwhile, the one out answers. An answer to a fetch
    /// that began before one of this PC's edits was applied may predate the edit: it is dropped,
    /// and the fetch the edit asked for (<see cref="ReloadAfterEditAsync"/>) follows it.
    /// </summary>
    internal async Task LoadAsync(bool force = false)
    {
        if (Host is not { } host) return;
        if (!host.IsPaired)
        {
            Render();
            return;
        }
        if (_loading) return;
        _loading = true;
        FetchedAt = host.Now;
        var started = ++_sequence;
        try
        {
            var snapshot = await host.Client.GetKitchenAsync(host.Lifetime);
            if (started < _lastEdit) return;
            var changed = force || _snapshot is null || LoadProblem is not null || snapshot.Revision != _snapshot.Revision ||
                KitchenText.LocalToday(host.Now) != _renderedDay || snapshot.Today != _snapshot.Today ||
                snapshot.Week?.Start != _snapshot.Week?.Start || snapshot.NextWeek?.Start != _snapshot.NextWeek?.Start;
            if (_failureLogged) host.Log("Kitchen loaded again");
            if (snapshot.Skipped > 0 && changed) host.Log($"Kitchen: skipped {snapshot.Skipped} unreadable records");
            _failureLogged = false;
            LoadProblem = null;
            _loadedAt = host.Now;
            _snapshot = snapshot;
            if (changed)
            {
                Render();
                host.KitchenChanged();
            }
            else
            {
                UpdatedText.Text = $"Updated {ReminderText.Time(host.Now)}";
            }
        }
        catch (OperationCanceledException) when (host.Lifetime.IsCancellationRequested)
        {
        }
        catch (Exception) when (started < _lastEdit)
        {
            // Overtaken by an edit; the fetch that follows it says how things stand.
        }
        catch (Exception exception)
        {
            if (!_failureLogged)
            {
                host.Log($"Kitchen load failed: {exception.GetType().Name}{(exception is HttpRequestException { StatusCode: { } code } ? $" {(int)code}" : "")}");
                _failureLogged = true;
            }
            LoadProblem = Describe(exception, loading: true);
            Render();
        }
        finally
        {
            _loading = false;
            if (_reloadRequested && !host.Lifetime.IsCancellationRequested)
            {
                _reloadRequested = false;
                await LoadAsync(force: true);
            }
        }
    }

    private static string Describe(Exception exception, bool loading = false)
    {
        var text = exception switch
        {
            BridgeException { StatusCode: HttpStatusCode.NotFound } when loading => "This calendar service has no Kitchen yet.",
            TaskCanceledException or TimeoutException => "The calendar service did not answer in time.",
            _ => exception.Message,
        };
        return text.Length > 160 ? text[..160] + "..." : text;
    }

    // ------------------------------------------------------------ what is shown

    internal void ShowPart(string part)
    {
        _part = part is ListPartName or PantryPartName ? part : WeekPartName;
        Render();
    }

    /// <summary>Opens a day of the meal plan in the details beside the week.</summary>
    internal void SelectDay(DateOnly date)
    {
        _selectedDate = date;
        // Chosen now, it stays chosen even when this draw is the first of a new day.
        if (Host is { } host) _renderedDay = KitchenText.LocalToday(host.Now);
        Render();
        DayScroll.ScrollToTop();
    }

    /// <summary>Draws the whole tab from what was last loaded.</summary>
    internal void Render()
    {
        var host = Host;
        var paired = host?.IsPaired ?? false;
        WeekPart.IsChecked = _part == WeekPartName;
        ListPart.IsChecked = _part == ListPartName;
        PantryPart.IsChecked = _part == PantryPartName;
        RefreshButton.IsEnabled = paired;
        UpdatedText.Text = _loadedAt is { } loaded && paired ? $"Updated {ReminderText.Time(loaded)}" : "";

        var snapshot = paired ? _snapshot : null;
        if (snapshot is null)
        {
            ShowMessage(paired);
            ListPartText.Text = "Shopping list";
            return;
        }
        MessagePanel.Visibility = Visibility.Collapsed;
        OfflineBar.Visibility = LoadProblem is null ? Visibility.Collapsed : Visibility.Visible;
        OfflineText.Text = $"{OfflineMessage} Showing what it said at {(_loadedAt is { } at ? ReminderText.Time(at) : "the last load")}.";
        OfflineBar.ToolTip = LoadProblem;
        WeekView.Visibility = _part == WeekPartName ? Visibility.Visible : Visibility.Collapsed;
        ShoppingView.Visibility = _part == ListPartName ? Visibility.Visible : Visibility.Collapsed;
        PantryView.Visibility = _part == PantryPartName ? Visibility.Visible : Visibility.Collapsed;

        var now = host!.Now;
        var today = KitchenText.LocalToday(now);
        // Tonight was open and the date has moved on: the new tonight is the one to open.
        if (_renderedDay is { } drawn && drawn != today && _selectedDate == drawn) _selectedDate = null;
        _renderedDay = today;
        RenderWeek(snapshot, today);
        RenderList(snapshot, now, today);
        RenderOnHand(snapshot, now, today);
    }

    private void ShowMessage(bool paired)
    {
        WeekView.Visibility = ShoppingView.Visibility = PantryView.Visibility = Visibility.Collapsed;
        OfflineBar.Visibility = Visibility.Collapsed;
        MessagePanel.Visibility = Visibility.Visible;
        var loading = paired && LoadProblem is null;
        MessageIcon.Source = (ImageSource)FindResource(loading ? "Icon.Kitchen" : "Icon.CloudOff");
        MessageText.Text = loading ? "Loading the kitchen..." : OfflineMessage;
        MessageDetail.Text = !paired ? "This PC is not paired. Pair it under Tools > Cloud Connection." : LoadProblem ?? "";
        MessageDetail.Visibility = MessageDetail.Text.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
        RetryLink.Visibility = paired && LoadProblem is not null ? Visibility.Visible : Visibility.Collapsed;
    }

    private void RenderWeek(KitchenSnapshot snapshot, DateOnly today)
    {
        var rows = new List<object>();
        var tonight = KitchenText.Tonight(snapshot, today);
        if (tonight is { } found)
        {
            rows.Add(new KitchenHeaderRow("Tonight", found.Day.Date.ToString("dddd", CultureInfo.InvariantCulture)));
            rows.Add(new MealDayRow(found.Plan, found.Day, snapshot.NoteFor(found.Day.Date), isTonight: true));
        }
        foreach (var (plan, label) in new[] { (snapshot.Week, "This week"), (snapshot.NextWeek, "Next week") })
        {
            if (plan is null) continue;
            var days = plan.Days.Where(day => tonight is not { } shown || day.Date != shown.Day.Date).ToList();
            if (days.Count > 0 || plan.Boxes.Count > 0) rows.Add(new KitchenHeaderRow(label, KitchenText.Range(plan.Start, plan.End)));
            rows.AddRange(days.Select(day => new MealDayRow(plan, day, snapshot.NoteFor(day.Date), isTonight: false)));
            foreach (var box in plan.Boxes)
            {
                rows.Add(new KitchenHeaderRow(box.Title.Length > 0 ? box.Title : "Also this week"));
                if (box.Items.Count > 0) rows.Add(new MealBoxRow(string.Join("\n", box.Items.Select(item => $"• {item}"))));
            }
        }

        var dayRows = rows.OfType<MealDayRow>().ToList();
        var selected = dayRows.FirstOrDefault(row => row.Day.Date == _selectedDate)
            ?? dayRows.FirstOrDefault(row => row.IsTonight)
            ?? dayRows.FirstOrDefault();
        _selectedDate = selected?.Day.Date;

        var hasPlan = snapshot.Week is not null || snapshot.NextWeek is not null;
        NoPlanPanel.Visibility = hasPlan ? Visibility.Collapsed : Visibility.Visible;
        WeekPanes.Visibility = hasPlan ? Visibility.Visible : Visibility.Collapsed;
        NoWeekBar.Visibility = hasPlan && snapshot.Week is null ? Visibility.Visible : Visibility.Collapsed;
        RenderPlanHeader(selected?.Plan ?? snapshot.Week ?? snapshot.NextWeek);

        var scroll = VisualTree.Descendant<ScrollViewer>(DayList);
        var offset = scroll?.VerticalOffset ?? 0;
        _binding = true;
        DayList.ItemsSource = rows;
        DayList.SelectedItem = selected;
        _binding = false;
        if (offset > 0) Dispatcher.InvokeAsync(() => scroll?.ScrollToVerticalOffset(offset), DispatcherPriority.Loaded);
        RenderDay(selected);
    }

    private void RenderPlanHeader(MealPlan? plan)
    {
        PlanHeader.Visibility = plan is null ? Visibility.Collapsed : Visibility.Visible;
        if (plan is null) return;
        var range = KitchenText.Range(plan.Start, plan.End);
        SetRich(PlanTitle, plan.Title.Length > 0 ? plan.Title : range.Length > 0 ? $"Meal plan · {range}" : "Meal plan");
        PlanDraft.Visibility = plan.Status == "draft" ? Visibility.Visible : Visibility.Collapsed;
        var line = string.Join(" · ", new[] { plan.Tagline }.Concat(plan.Meta).Where(part => part.Length > 0));
        SetRich(PlanTagline, line);
        PlanTagline.Visibility = line.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    /// <summary>The open day: dinner, the day's note, the recipe, and how it went.</summary>
    private void RenderDay(MealDayRow? row)
    {
        DayDetails.Visibility = row is null ? Visibility.Collapsed : Visibility.Visible;
        if (row is null) return;
        var day = row.Day;
        var when = day.Date.ToString("dddd, MMMM d", CultureInfo.InvariantCulture);
        DayWhen.Text = (row.IsTonight ? $"Tonight · {when}" : when) + (day.IsLeftovers ? " · Leftovers" : "");
        SetRich(DayDinner, day.Dinner);
        SetRich(DayBase, day.Base.Length > 0 ? $"With {day.Base}" : "");
        DayBase.Visibility = day.Base.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
        SetRich(DayNoteText, day.Note);
        DayNoteBar.Visibility = day.Note.Length > 0 ? Visibility.Visible : Visibility.Collapsed;

        // Leftovers nights show no recipe.
        var recipe = day.IsLeftovers ? null : row.Plan.RecipeFor(day);
        RecipePanel.Visibility = recipe is null ? Visibility.Collapsed : Visibility.Visible;
        if (recipe is not null)
        {
            var showTitle = recipe.Title.Length > 0 &&
                !string.Equals(KitchenText.StripBold(recipe.Title), KitchenText.StripBold(day.Dinner), StringComparison.OrdinalIgnoreCase);
            SetRich(RecipeTitle, showTitle ? recipe.Title : "");
            RecipeTitle.Visibility = showTitle ? Visibility.Visible : Visibility.Collapsed;
            SetRich(RecipeSub, recipe.Sub);
            RecipeSub.Visibility = recipe.Sub.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
            SetRich(IngredientsText, recipe.Ingredients);
            IngredientsPart.Visibility = recipe.Ingredients.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
            StepsList.ItemsSource = recipe.Steps.Select((step, index) => new StepRow($"{index + 1}.", step)).ToList();
            StepsPart.Visibility = recipe.Steps.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
            SetRich(RecipeNote, recipe.Note.Length > 0 ? $"**Note:** {recipe.Note}" : "");
            RecipeNote.Visibility = recipe.Note.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
        }

        var note = row.Note;
        CookedYes.IsChecked = note.Cooked == "yes";
        CookedSkipped.IsChecked = note.Cooked == "skipped";
        RatingLoved.IsChecked = note.Rating == "loved";
        RatingOk.IsChecked = note.Rating == "ok";
        RatingNo.IsChecked = note.Rating == "no";

        // A note being typed survives a refresh and a switch to another day and back; each day shows
        // its own draft, or what is saved for it.
        _noteDate = day.Date;
        _noteBaseline = note.Note;
        if (_noteDrafts.TryGetValue(day.Date, out var draft) && draft == note.Note) _noteDrafts.Remove(day.Date);
        DinnerNoteBox.Text = _noteDrafts.TryGetValue(day.Date, out draft) ? draft : note.Note;
        SaveNoteButton.IsEnabled = DinnerNoteBox.Text != _noteBaseline;
    }

    private void RenderList(KitchenSnapshot snapshot, DateTimeOffset now, DateOnly today)
    {
        var (need, ordered, got) = KitchenText.Arrange(snapshot.List, now);
        var rows = new List<object>();
        if (need.Count > 0)
        {
            rows.Add(new KitchenHeaderRow("To buy", $"({need.Count})"));
            rows.AddRange(need.Select(item => new ShoppingRow(item, today)));
        }
        if (ordered.Count > 0)
        {
            rows.Add(new KitchenHeaderRow("On order", $"({ordered.Count})"));
            rows.AddRange(ordered.Select(item => new ShoppingRow(item, today)));
        }
        if (got.Count > 0)
        {
            rows.Add(new KitchenHeaderRow("Got it", $"({got.Count}) · the last 7 days; untick to put one back"));
            rows.AddRange(got.Select(item => new ShoppingRow(item, today)));
        }
        ShoppingItems.ItemsSource = rows;
        ListEmpty.Visibility = rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        var open = need.Count + ordered.Count;
        ListPartText.Text = open > 0 ? $"Shopping list ({open})" : "Shopping list";
    }

    /// <summary>
    /// The On hand part: the header, the corrections (the newest 3, or all), and Claude's estimate
    /// by section, what is probably out and running low first.
    /// </summary>
    private void RenderOnHand(KitchenSnapshot snapshot, DateTimeOffset now, DateOnly today)
    {
        OnHandHeader.Text = KitchenText.OnHandHeader(snapshot.Pantry);
        LowOnlyBox.IsChecked = _lowAndOutOnly;
        var rows = new List<object>();
        var corrections = KitchenText.ArrangeCorrections(snapshot.Corrections, now, all: true);
        if (corrections.Count > 0)
        {
            var waiting = corrections.Count(correction => correction.IsOpen);
            rows.Add(new KitchenHeaderRow("Your corrections", waiting > 0 ? $"({waiting} waiting for Claude)" : $"({corrections.Count})"));
            var shown = _allCorrections ? corrections : KitchenText.ArrangeCorrections(snapshot.Corrections, now, all: false);
            rows.AddRange(shown.Select(correction => new CorrectionRow(correction, today)));
            if (corrections.Count > KitchenText.CorrectionsShown)
                rows.Add(new ShowAllRow(_allCorrections ? $"Show the newest {KitchenText.CorrectionsShown}" : $"Show all {corrections.Count}"));
        }
        var sections = KitchenText.OnHand(snapshot.Pantry, _lowAndOutOnly);
        if (snapshot.Pantry.Count == 0) rows.Add(new KitchenNoteRow(EmptyPantryMessage));
        else if (sections.Count == 0) rows.Add(new KitchenNoteRow("Nothing is running low or out."));
        foreach (var (section, items) in sections)
        {
            rows.Add(new KitchenHeaderRow(section, $"({items.Count})"));
            rows.AddRange(items.Select(item => new EstimateRow(item, item.Id == _selectedPantryId)));
        }
        if (!rows.OfType<EstimateRow>().Any(row => row.IsSelected)) _selectedPantryId = null;
        // The item a note was being started about is gone (Claude removed it): the note is a plain one now.
        if (_tellItemId is { } about && !snapshot.Pantry.Any(item => item.Id == about))
        {
            _tellItemId = null;
            _tellItemName = "";
        }
        OnHandItems.ItemsSource = rows;
        RenderTellItem();
    }

    /// <summary>Opens the correction buttons on one row of the estimate (null closes them).</summary>
    internal void SelectPantryItem(string? id)
    {
        _selectedPantryId = id;
        EstimateRow? selected = null;
        foreach (var row in OnHandItems.Items.OfType<EstimateRow>())
        {
            row.IsSelected = row.Item.Id == id;
            if (row.IsSelected) selected = row;
        }
        // Once its buttons are laid out, bring all of the row into view.
        if (selected is not null)
        {
            Dispatcher.InvokeAsync(() =>
            {
                if (OnHandItems.ItemContainerGenerator.ContainerFromItem(selected) is FrameworkElement row) row.BringIntoView();
            }, DispatcherPriority.Loaded);
        }
    }

    /// <summary>Shows Running low and Probably out only, or everything.</summary>
    internal void ShowLowAndOutOnly(bool only)
    {
        _lowAndOutOnly = only;
        Render();
    }

    private void RenderTellItem()
    {
        var linked = _tellItemId is not null;
        AboutItem.Visibility = linked ? Visibility.Visible : Visibility.Collapsed;
        AboutItemText.Text = linked ? $"About {_tellItemName}" : "";
        TellHint.Visibility = linked ? Visibility.Collapsed : Visibility.Visible;
    }

    // ------------------------------------------------------------ edits

    /// <summary>Adds a line to the shopping list. An open line of the same name comes back instead.</summary>
    internal async Task AddItemAsync(string text)
    {
        var name = text.Trim();
        if (name.Length == 0) return;
        await EditAsync("add", "Could not add it to the shopping list", async host =>
        {
            var result = await host.Client.AddKitchenItemAsync(name, cancellationToken: host.Lifetime);
            Apply(result.Item);
            if (AddBox.Text.Trim() == name) AddBox.Clear();
            host.ShowActionBar(result.Created ? $"Added to the shopping list: {result.Item.Name}" : $"Already on the shopping list: {result.Item.Name}", null);
        });
    }

    /// <summary>Ticks a line off (got) or puts it back on the list (need).</summary>
    internal async Task SetItemGotAsync(string id, bool got)
    {
        var item = _snapshot?.List.FirstOrDefault(existing => existing.Id == id);
        var status = got ? ShoppingStatus.Got : ShoppingStatus.Need;
        if (item is null || item.Status == status)
        {
            Render();
            return;
        }
        await EditAsync(id, got ? "Could not tick it off" : "Could not put it back on the list", async host =>
        {
            var result = await host.Client.UpdateKitchenItemAsync(id, new ShoppingItemChange(Status: status), host.Lifetime);
            Apply(result.Item);
        });
    }

    /// <summary>Removes a line from the list, with Undo in the bar that confirms it.</summary>
    internal async Task RemoveItemAsync(string id)
    {
        if (_snapshot?.List.FirstOrDefault(existing => existing.Id == id) is not { } item) return;
        await EditAsync(id, "Could not remove it from the list", async host =>
        {
            await host.Client.DeleteKitchenItemAsync(id, host.Lifetime);
            RemoveListItem(id);
            host.ShowActionBar($"Removed from the shopping list: {item.Name}", () => RestoreItemAsync(item));
        });
    }

    /// <summary>
    /// Undo of a remove: the line comes back as it was, to buy, on order with its order, or bought
    /// (an older bridge puts it back to buy; what it answers is shown). Its own busy key, so no
    /// other edit still out can swallow it.
    /// </summary>
    private Task RestoreItemAsync(ShoppingItem item) =>
        EditAsync($"undo:{item.Id}", "Could not put it back on the list", async host =>
        {
            var result = await host.Client.AddKitchenItemAsync(item.Name, item.Qty.Length > 0 ? item.Qty : null,
                item.Note.Length > 0 ? item.Note : null, new ShoppingItemRestore(item.Status, item.OrderRef), host.Lifetime);
            Apply(result.Item);
        });

    /// <summary>
    /// We're out or We have it: applied at once. Out also puts it on the shopping list (rule 2);
    /// the bar shows Claude's reply.
    /// </summary>
    internal async Task CorrectAsync(string itemId, string kind)
    {
        if (_snapshot?.Pantry.FirstOrDefault(existing => existing.Id == itemId) is not { } item || kind is not (CorrectionKind.Out or CorrectionKind.Have))
        {
            Render();
            return;
        }
        await EditAsync($"correct:{itemId}", kind == CorrectionKind.Out ? "Could not tell Claude it's out" : "Could not tell Claude you have it", async host =>
        {
            var result = await host.Client.AddCorrectionAsync(kind, itemId: itemId, clientId: KitchenJson.NewClientId(), cancellationToken: host.Lifetime);
            ApplyCorrection(result);
            var reply = result.Correction.Reply.Length > 0 ? result.Correction.Reply
                : kind == CorrectionKind.Have ? result.RemovedListItemId is not null ? "Marked as on hand and taken off the shopping list." : "Marked as on hand."
                : result.ListItem is not null ? "Added to the shopping list." : "Marked out.";
            host.ShowActionBar($"{item.Name}: {KitchenText.StripBold(reply)}", null);
        });
    }

    /// <summary>Something else...: the Tell Claude box gets "Item: " and the note will be about that item.</summary>
    internal void StartNoteAbout(string itemId)
    {
        if (_snapshot?.Pantry.FirstOrDefault(existing => existing.Id == itemId) is not { } item) return;
        _tellItemId = item.Id;
        _tellItemName = item.Name;
        TellBox.Text = $"{item.Name}: ";
        TellBox.CaretIndex = TellBox.Text.Length;
        RenderTellItem();
        if (Window.GetWindow(this) is { IsActive: true }) TellBox.Focus();
    }

    /// <summary>
    /// Sends what is in the Tell Claude box as a note. It waits, open, until Claude answers. A note
    /// sent again after no answer came (the server may have stored it) goes with the same client id,
    /// so it is stored once.
    /// </summary>
    internal async Task SendNoteAsync(string text)
    {
        var note = text.Trim();
        if (note.Length == 0) return;
        var itemId = _tellItemId;
        var clientId = _unsentNote is { } unsent && unsent.Text == note && unsent.ItemId == itemId ? unsent.ClientId : KitchenJson.NewClientId();
        await EditAsync("note", "Could not send it to Claude", async host =>
        {
            _unsentNote = (note, itemId, clientId);
            var result = await host.Client.AddCorrectionAsync(CorrectionKind.Note, note, itemId, clientId, host.Lifetime);
            _unsentNote = null;
            ApplyCorrection(result);
            if (TellBox.Text.Trim() == note) ClearTellBox();
        });
    }

    /// <summary>Takes back a note Claude has not answered yet, with Undo in the bar that sends it again.</summary>
    internal async Task WithdrawAsync(string id)
    {
        if (_snapshot?.Corrections.FirstOrDefault(existing => existing.Id == id) is not { IsOpen: true } correction) return;
        await EditAsync(id, "Could not withdraw it", async host =>
        {
            await host.Client.WithdrawCorrectionAsync(id, host.Lifetime);
            Edited(kitchen => kitchen.Corrections.RemoveAll(existing => existing.Id == id));
            host.ShowActionBar($"Withdrawn: {KitchenText.CorrectionSaid(correction)}", () => ResendAsync(correction));
        });
    }

    /// <summary>Undo of a withdraw: the note goes again, as a new one. Its own busy key, as every Undo has.</summary>
    private Task ResendAsync(KitchenCorrection correction) =>
        EditAsync($"undo:{correction.Id}", "Could not send it to Claude", async host =>
        {
            var result = await host.Client.AddCorrectionAsync(CorrectionKind.Note, correction.Text, correction.ItemId,
                KitchenJson.NewClientId(), host.Lifetime);
            ApplyCorrection(result);
        });

    private void ClearTellBox()
    {
        TellBox.Clear();
        _tellItemId = null;
        _tellItemName = "";
        _unsentNote = null;
        RenderTellItem();
    }

    /// <summary>
    /// A correction the server sent back, with the pantry item it changed, the list item it added
    /// and the list item it took off. A repeat of one already stored replaces it, never adds a second.
    /// </summary>
    private void ApplyCorrection(CorrectionResult result) => Edited(kitchen =>
    {
        var index = kitchen.Corrections.FindIndex(existing => existing.Id == result.Correction.Id);
        if (index >= 0) kitchen.Corrections[index] = result.Correction;
        else kitchen.Corrections.Add(result.Correction);
        if (result.Item is { } item) ApplyPantry(item);
        if (result.ListItem is { } listed) Apply(listed);
        if (result.RemovedListItemId is { } removed) RemoveListItem(removed);
    });

    /// <summary>How a dinner went. Only the fields given change; "" clears one.</summary>
    internal async Task SetDinnerAsync(DateOnly date, string? cooked = null, string? rating = null, string? note = null)
    {
        if (_snapshot is null) return;
        var key = KitchenText.DateKey(date);
        var field = cooked is not null ? "cooked" : rating is not null ? "rating" : "note";
        await EditAsync($"{field}:{key}", "Could not save how dinner went", async host =>
        {
            var result = await host.Client.PutDinnerNoteAsync(key, new DinnerNoteChange(cooked, rating, note), host.Lifetime);
            Edited(kitchen => kitchen.Notes[result.Date] = result.Note);
            // What was typed for that day is saved, so it is no longer a draft (unless more was typed meanwhile).
            if (note is not null && _noteDrafts.TryGetValue(date, out var draft) && draft.Trim() == note) _noteDrafts.Remove(date);
        });
    }

    /// <summary>
    /// Runs one edit: the request, then the answer applied and drawn, then a fresh fetch. When the
    /// server does not take it, nothing changes on screen and the window reports the reason. When
    /// what it was about is gone (removed on another device, or answered by Claude), the kitchen is
    /// fetched again and the status bar says so, without a message box.
    /// </summary>
    private async Task EditAsync(string key, string heading, Func<IKitchenHost, Task> edit)
    {
        if (Host is not { } host || _snapshot is null || !_busy.Add(key))
        {
            // A control that was clicked shows its new state at once; put it back.
            Render();
            return;
        }
        try
        {
            host.SetStatus("Saving to the kitchen...");
            await edit(host);
            Render();
            host.KitchenChanged();
            host.SetStatus("Saved to the kitchen");
            await ReloadAfterEditAsync();
        }
        catch (OperationCanceledException) when (host.Lifetime.IsCancellationRequested)
        {
        }
        catch (BridgeException exception) when (exception.StatusCode == HttpStatusCode.NotFound)
        {
            LastProblem = $"{heading}: {Describe(exception)}";
            host.SetStatus($"{heading}: it was changed or removed elsewhere.");
            Render();
            await ReloadAfterEditAsync();
        }
        catch (Exception exception)
        {
            var reason = Describe(exception);
            LastProblem = $"{heading}: {reason}";
            host.SetStatus($"{heading}.");
            Render();
            host.ReportKitchenProblem(heading, reason);
        }
        finally
        {
            _busy.Remove(key);
        }
    }

    /// <summary>
    /// The fetch an edit asks for. When one is already out it may have been answered before the
    /// edit, so another follows it. Only an edit asks for that.
    /// </summary>
    private async Task ReloadAfterEditAsync()
    {
        if (_loading) _reloadRequested = true;
        else await LoadAsync(force: true);
    }

    /// <summary>
    /// Changes what is shown with an answer from the server. From here on, a fetch that began
    /// earlier may predate the change, and its answer is dropped.
    /// </summary>
    private void Edited(Action<KitchenSnapshot> change)
    {
        if (_snapshot is null) return;
        _lastEdit = ++_sequence;
        change(_snapshot);
    }

    /// <summary>Puts a pantry item the server sent back in place of the one with its id, or adds it.</summary>
    private void ApplyPantry(PantryItem item) => Edited(kitchen =>
    {
        var index = kitchen.Pantry.FindIndex(existing => existing.Id == item.Id);
        if (index >= 0) kitchen.Pantry[index] = item;
        else kitchen.Pantry.Add(item);
    });

    /// <summary>Puts a list item the server sent back in place of the one with its id, or adds it.</summary>
    private void Apply(ShoppingItem item) => Edited(kitchen =>
    {
        var index = kitchen.List.FindIndex(existing => existing.Id == item.Id);
        if (index >= 0) kitchen.List[index] = item;
        else kitchen.List.Add(item);
    });

    private void RemoveListItem(string id) => Edited(kitchen => kitchen.List.RemoveAll(existing => existing.Id == id));

    // ------------------------------------------------------------ controls

    private void Part_Click(object sender, RoutedEventArgs e)
    {
        var part = sender == ListPart ? ListPartName : sender == PantryPart ? PantryPartName : WeekPartName;
        ShowPart(part);
        Host?.KitchenPartChanged(part);
        // Ready to type into, but only in a window that already has the focus.
        if (part == ListPartName && Window.GetWindow(this) is { IsActive: true }) AddBox.Focus();
    }

    private async void Refresh_Click(object sender, RoutedEventArgs e) => await LoadAsync(force: true);

    private void DayList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_binding) return;
        if (DayList.SelectedItem is MealDayRow row)
        {
            _selectedDate = row.Day.Date;
            RenderPlanHeader(row.Plan);
            RenderDay(row);
            DayScroll.ScrollToTop();
            return;
        }
        // Nothing selected (Ctrl+click): keep the open day.
        _binding = true;
        DayList.SelectedItem = DayList.Items.OfType<MealDayRow>().FirstOrDefault(day => day.Day.Date == _selectedDate);
        _binding = false;
    }

    private async void Cooked_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not RadioButton { Tag: string value } || SelectedDay is not { } row || _snapshot is null) return;
        // Choosing the one already chosen clears it.
        await SetDinnerAsync(row.Day.Date, cooked: _snapshot.NoteFor(row.Day.Date).Cooked == value ? "" : value);
    }

    private async void Rating_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not RadioButton { Tag: string value } || SelectedDay is not { } row || _snapshot is null) return;
        await SetDinnerAsync(row.Day.Date, rating: _snapshot.NoteFor(row.Day.Date).Rating == value ? "" : value);
    }

    /// <summary>What is typed and differs from the saved note is that day's draft until it is saved.</summary>
    private void DinnerNoteBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (_noteDate is not { } date)
        {
            SaveNoteButton.IsEnabled = false;
            return;
        }
        var typed = DinnerNoteBox.Text != _noteBaseline;
        if (typed) _noteDrafts[date] = DinnerNoteBox.Text;
        else _noteDrafts.Remove(date);
        SaveNoteButton.IsEnabled = typed;
    }

    private async void SaveNote_Click(object sender, RoutedEventArgs e)
    {
        if (SelectedDay is { } row) await SetDinnerAsync(row.Day.Date, note: DinnerNoteBox.Text.Trim());
    }

    private async void Add_Click(object sender, RoutedEventArgs e) => await AddItemAsync(AddBox.Text);

    private async void AddBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            e.Handled = true;
            await AddItemAsync(AddBox.Text);
        }
        else if (e.Key == Key.Escape && AddBox.Text.Length > 0)
        {
            e.Handled = true;
            AddBox.Clear();
        }
    }

    private async void ItemTick_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is ShoppingRow row) await SetItemGotAsync(row.Item.Id, !row.IsGot);
    }

    private async void ItemRemove_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is ShoppingRow row) await RemoveItemAsync(row.Item.Id);
    }

    private void EstimateRow_Click(object sender, MouseButtonEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is EstimateRow row) SelectPantryItem(row.IsSelected ? null : row.Item.Id);
    }

    private async void WeAreOut_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is EstimateRow row) await CorrectAsync(row.Item.Id, CorrectionKind.Out);
    }

    private async void WeHaveIt_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is EstimateRow row) await CorrectAsync(row.Item.Id, CorrectionKind.Have);
    }

    private void SomethingElse_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is EstimateRow row) StartNoteAbout(row.Item.Id);
    }

    private async void Send_Click(object sender, RoutedEventArgs e) => await SendNoteAsync(TellBox.Text);

    private async void TellBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            e.Handled = true;
            await SendNoteAsync(TellBox.Text);
        }
        else if (e.Key == Key.Escape && (TellBox.Text.Length > 0 || _tellItemId is not null))
        {
            e.Handled = true;
            ClearTellBox();
        }
    }

    /// <summary>An emptied box is no longer about the item it was started for. A changed note is a new one, with a new client id.</summary>
    private void TellBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (_unsentNote is { } unsent && TellBox.Text.Trim() != unsent.Text) _unsentNote = null;
        if (TellBox.Text.Length > 0 || _tellItemId is null) return;
        _tellItemId = null;
        _tellItemName = "";
        RenderTellItem();
    }

    private void AboutItemClear_Click(object sender, RoutedEventArgs e)
    {
        var prefix = $"{_tellItemName}: ";
        if (TellBox.Text.StartsWith(prefix, StringComparison.Ordinal)) TellBox.Text = TellBox.Text[prefix.Length..];
        _tellItemId = null;
        _tellItemName = "";
        RenderTellItem();
    }

    private void LowOnly_Click(object sender, RoutedEventArgs e) => ShowLowAndOutOnly(LowOnlyBox.IsChecked == true);

    private void ShowAll_Click(object sender, RoutedEventArgs e)
    {
        _allCorrections = !_allCorrections;
        Render();
    }

    private async void Withdraw_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is CorrectionRow row) await WithdrawAsync(row.Correction.Id);
    }
}
