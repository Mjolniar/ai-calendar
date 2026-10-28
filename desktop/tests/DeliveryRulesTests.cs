using System.Globalization;

namespace AICalendar.Desktop.Tests;

/// <summary>What makes a reminder a delivery, and what follows from it. No windows involved.</summary>
public sealed class DeliveryRulesTests
{
    private static DateTimeOffset Local(int year, int month, int day, int hour, int minute)
    {
        var wall = new DateTime(year, month, day, hour, minute, 0, DateTimeKind.Unspecified);
        return new DateTimeOffset(wall, TimeZoneInfo.Local.GetUtcOffset(wall));
    }

    private static CalendarEvent Event(string id, DateTimeOffset start, string domain, params int[] offsets) => new()
    {
        Id = id,
        Title = id,
        StartAt = start.UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'", CultureInfo.InvariantCulture),
        Domain = domain,
        ReminderOffsetsMinutes = [.. offsets],
    };

    [Fact]
    public void OnlyTheBridgeFilingItMakesADelivery()
    {
        var fed = new CalendarEvent { Id = "a", Title = "Desk lamp", Domain = "delivery" };
        var tagged = new CalendarEvent { Id = "b", Title = "Desk lamp", Category = "delivery" };
        var worded = new CalendarEvent { Id = "c", Title = "Package delivery arrives, UPS shipment", Domain = "life" };
        var unfiled = new CalendarEvent { Id = "d", Title = "Package delivery arrives" };
        var lifeTagged = new CalendarEvent { Id = "e", Title = "Print the return label", Domain = "life", Category = "delivery" };
        var prescription = new CalendarEvent { Id = "f", Title = "Prescription from the pharmacy", Domain = "delivery", Category = "medical" };

        Assert.True(fed.IsDelivery);
        Assert.True(tagged.IsDelivery);
        Assert.False(worded.IsDelivery);
        Assert.False(unfiled.IsDelivery);
        // The feed the bridge gave wins over the category.
        Assert.False(lifeTagged.IsDelivery);

        // What a Done or Undo from this PC sends back: the delivery stays a delivery.
        Assert.Equal("delivery", fed.ResolvedDomain);
        Assert.Equal("delivery", tagged.ResolvedDomain);
        Assert.Equal("life", worded.ResolvedDomain);
        Assert.Equal("life", unfiled.ResolvedDomain);
        Assert.Equal("life", lifeTagged.ResolvedDomain);

        Assert.Equal("delivery", fed.ResolvedCategory);
        Assert.Equal("general", unfiled.ResolvedCategory);
        // A delivery keeps a category it was given, and is still a delivery.
        Assert.Equal("medical", prescription.ResolvedCategory);
        Assert.True(prescription.IsDelivery);
    }

    [Fact]
    public void ADeliveryHasNoAlertOffsetsAndIsCurrentThroughItsDay()
    {
        var start = Local(2026, 10, 6, 9, 0);
        var parcel = Event("parcel", start, "delivery", 1440, 0);
        var visit = Event("visit", start, "life", 1440, 0);

        Assert.Empty(parcel.AlertOffsets);
        Assert.Equal([1440, 0], visit.AlertOffsets);

        Assert.Equal(Local(2026, 10, 7, 0, 0).AddMilliseconds(-1), parcel.CurrentUntil);
        Assert.Equal(visit.Start, visit.CurrentUntil);
        Assert.False(parcel.IsArchivedAt(Local(2026, 10, 6, 21, 0)));
        Assert.True(visit.IsArchivedAt(Local(2026, 10, 6, 21, 0)));
        Assert.True(parcel.IsArchivedAt(Local(2026, 10, 7, 0, 1)));

        parcel.Done = true;
        Assert.True(parcel.IsArchivedAt(Local(2026, 10, 5, 12, 0)));

        // The day the clocks go back is 25 hours long; the delivery still ends at the next midnight.
        var fallBack = Event("late", Local(2026, 11, 1, 9, 0), "delivery");
        Assert.Equal(Local(2026, 11, 2, 0, 0).AddMilliseconds(-1), fallBack.CurrentUntil);

        // A delivery whose date could not be read is not kept on the list forever.
        var unreadable = new CalendarEvent { Id = "x", Title = "Parcel", StartAt = "soon", Domain = "delivery" };
        Assert.True(unreadable.IsArchivedAt(Local(2026, 10, 1, 14, 5)));
    }

    [Fact]
    public void ABirthdayStaysCurrentUntilTheDayAfter()
    {
        var birthday = Event("priya", Local(2026, 10, 3, 9, 0), "life");
        birthday.Title = "Priya's birthday";
        var errand = Event("errand", Local(2026, 10, 3, 9, 0), "life");
        errand.Title = "Pick up the cake";

        // 9:01 AM on the day: the errand is filed, the birthday is not.
        Assert.False(birthday.IsArchivedAt(Local(2026, 10, 3, 9, 1)));
        Assert.True(errand.IsArchivedAt(Local(2026, 10, 3, 9, 1)));
        Assert.False(birthday.IsArchivedAt(Local(2026, 10, 3, 23, 59)));
        Assert.True(birthday.IsArchivedAt(Local(2026, 10, 4, 0, 0)));
        Assert.Equal(Local(2026, 10, 4, 0, 0).AddMilliseconds(-1), birthday.CurrentUntil);

        // Before the day it is an ordinary upcoming reminder; once done it is filed at once.
        Assert.False(birthday.IsArchivedAt(Local(2026, 10, 1, 12, 0)));
        birthday.Done = true;
        Assert.True(birthday.IsArchivedAt(Local(2026, 10, 1, 12, 0)));

        // It keeps its alerts: only when it is filed changes.
        Assert.False(birthday.IsDelivery);
        Assert.Equal([1440], Event("b", Local(2026, 10, 3, 9, 0), "life", 1440).AlertOffsets);
    }

    [Theory]
    [InlineData("Priya's birthday", true)]
    [InlineData("Jordan Alex Rivera's Birthday", true)]
    [InlineData("BIRTHDAY: Dad turns 60", true)]
    [InlineData("Kids' birthdays this month", true)]
    [InlineData("Sam's b-day dinner", true)]
    [InlineData("Sam bday", true)]
    [InlineData("Pay rent", false)]
    [InlineData("Birthdaycake order", false)]
    [InlineData("Rebirthday", false)]
    public void ABirthdayIsRecognizedByItsTitle(string title, bool expected)
    {
        var item = new CalendarEvent { Id = "t", Title = title, Notes = "For Priya's birthday", Location = "Birthday Shop" };
        Assert.Equal(expected, item.IsBirthday);
        Assert.Equal(expected, item.IsListedAllDay);

        // The answer follows the title when it changes.
        item.Title = "Priya's birthday";
        Assert.True(item.IsBirthday);
        item.Title = "Order flowers";
        Assert.False(item.IsBirthday);
    }
}
