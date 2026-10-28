using System.ComponentModel;
using System.Globalization;

namespace AICalendar.Desktop;

/// <summary>A caption in a kitchen list: "To buy (3)", "Tonight", "Canned &amp; jarred (4)". Never selected.</summary>
public sealed record KitchenHeaderRow(string Label, string Detail = "")
{
    public bool Selectable => false;
}

/// <summary>A day of the meal plan in the week list. Everything is worked out here, so the template only binds.</summary>
public sealed class MealDayRow(MealPlan plan, MealDay day, DinnerNote note, bool isTonight)
{
    public MealPlan Plan { get; } = plan;
    public MealDay Day { get; } = day;
    public DinnerNote Note { get; } = note;
    public bool IsTonight { get; } = isTonight;
    public bool Selectable => true;

    public string DayName => Day.Date.ToString("ddd", CultureInfo.InvariantCulture);
    public string DayDate => Day.Date.ToString("M/d", CultureInfo.InvariantCulture);
    public string Dinner => Day.Dinner;

    /// <summary>"sweet potato · Cooked · Loved it"; "Leftovers" on a leftovers night without a base.</summary>
    public string Secondary
    {
        get
        {
            var parts = new List<string>();
            if (Day.Base.Length > 0) parts.Add(KitchenText.StripBold(Day.Base));
            else if (Day.IsLeftovers) parts.Add("Leftovers");
            if (KitchenText.HowItWent(Note) is { Length: > 0 } went) parts.Add(went);
            return string.Join(" · ", parts);
        }
    }
}

/// <summary>The items of one of the week's boxes ("Breakfast &amp; lunch"), one per line.</summary>
public sealed record MealBoxRow(string Text)
{
    public bool Selectable => false;
}

/// <summary>A numbered step of a recipe.</summary>
public sealed record StepRow(string Number, string Text);

/// <summary>One line of the shopping list.</summary>
public sealed class ShoppingRow(ShoppingItem item, DateOnly today)
{
    public ShoppingItem Item { get; } = item;
    public string Name => Item.Name;
    public string Detail { get; } = KitchenText.ItemDetail(item, today);
    public bool HasDetail => Detail.Length > 0;
    public bool IsGot => Item.Status == ShoppingStatus.Got;
    public string TickToolTip => IsGot ? "Not bought after all: back on the list" : "Got it";
}

/// <summary>
/// One thing in Claude's estimate: its name, a chip in plain words and why Claude thinks so.
/// Selected, it shows We're out, We have it and Something else.
/// </summary>
public sealed class EstimateRow(PantryItem item, bool isSelected = false) : INotifyPropertyChanged
{
    private bool _isSelected = isSelected;

    public PantryItem Item { get; } = item;

    /// <summary>The row whose correction buttons show.</summary>
    public bool IsSelected
    {
        get => _isSelected;
        set
        {
            if (_isSelected == value) return;
            _isSelected = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(IsSelected)));
        }
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    public string Name => Item.Name;
    public string Basis => Item.Basis;
    public bool HasBasis => Item.Basis.Length > 0;
    public string Chip => KitchenText.EstimateLabel(Item.Status);
    public bool IsLow => Item.Status == PantryStatus.Low;
    public bool IsOut => Item.Status == PantryStatus.Out;
}

/// <summary>A correction someone sent: what they said, Claude's answer or "Waiting for Claude", and who and when.</summary>
public sealed class CorrectionRow(KitchenCorrection correction, DateOnly today)
{
    public KitchenCorrection Correction { get; } = correction;
    public bool Selectable => false;
    public string Said => KitchenText.CorrectionSaid(Correction);
    public string Answer => KitchenText.CorrectionAnswer(Correction);
    public bool IsOpen => Correction.IsOpen;
    public string Status => Correction.Status;

    public string Byline
    {
        get
        {
            var parts = new List<string>();
            if (Correction.AddedBy.Length > 0) parts.Add(Correction.AddedBy);
            if (Correction.AddedAt is { } added) parts.Add(KitchenText.DayWord(DateOnly.FromDateTime(added.LocalDateTime), today));
            return string.Join(" · ", parts);
        }
    }
}

/// <summary>"Show all 7" or "Show the newest 3" under the corrections.</summary>
public sealed record ShowAllRow(string Label)
{
    public bool Selectable => false;
}

/// <summary>A line of plain text in a list: the empty pantry, an empty filter.</summary>
public sealed record KitchenNoteRow(string Text)
{
    public bool Selectable => false;
}
