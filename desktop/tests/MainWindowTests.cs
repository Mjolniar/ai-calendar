using System.Windows;

namespace AICalendar.Desktop.Tests;

/// <summary>What the main window lists, counted and read from its controls. No network, no tray icon.</summary>
[Collection(UiCollection.Name)]
public sealed class MainWindowTests(UiFixture ui)
{
    private static string[] Rows(MainWindow window) => window.ReminderList.Items.Cast<object>().Select(row => row switch
    {
        GroupHeaderRow caption => $"{caption.Label} {caption.CountText}",
        ReminderRow reminder => reminder.Event.Id,
        _ => "?",
    }).ToArray();

    [Fact]
    public void TabsCountWhatIsStillAhead() => ui.Run(() =>
    {
        var window = UiFixture.Open(Sample.Store());
        try
        {
            Assert.Equal("Life (5)", window.LifeTab.Content);
            Assert.Equal("School (9)", window.SchoolTab.Content);
            Assert.Equal("All (14)", window.AllTab.Content);
            Assert.Equal("5 reminders", window.CountText.Text);
            Assert.Equal(
                ["Today (1)", "dentist", "Tomorrow (1)", "rent", "Next week (1)", "flight", "Later this month (1)", "oil", "November (1)", "registration"],
                Rows(window));
        }
        finally { window.Close(); }
    });

    [Fact]
    public void ArchivedListsDoneAndPastNewestFirst() => ui.Run(() =>
    {
        var window = UiFixture.Open(Sample.Store());
        try
        {
            window.ShowSection("archived");
            UiFixture.Settle();
            // Thursday, October 1: Monday the 28th is earlier this week, the 24th and 25th were last week.
            Assert.Equal(["Earlier this week (1)", "insurance", "Last week (2)", "quiz3", "lab4", "August (1)", "haircut"], Rows(window));
            Assert.Equal("4 reminders", window.CountText.Text);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void FindNarrowsTheListAndSaysWhenNothingMatches() => ui.Run(() =>
    {
        var window = UiFixture.Open(Sample.Store());
        try
        {
            window.ShowSection("school");
            window.FindBox.Text = "chm110l lab";
            UiFixture.Settle();
            Assert.Equal(["Today (1)", "lab5", "Later this month (1)", "lab6"], Rows(window));
            Assert.Equal("2 found", window.CountText.Text);
            Assert.Equal(Visibility.Collapsed, window.EmptyPanel.Visibility);

            window.FindBox.Text = "zebra";
            UiFixture.Settle();
            Assert.Empty(Rows(window));
            Assert.Equal(Visibility.Visible, window.EmptyPanel.Visibility);
            Assert.Equal("No reminders match \"zebra\".", window.EmptyText.Text);
            Assert.Equal(Visibility.Visible, window.EmptyLink.Visibility);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void ClassFilterListsOneCourse() => ui.Run(() =>
    {
        var window = UiFixture.Open(Sample.Store());
        try
        {
            window.ShowSection("school");
            UiFixture.Settle();
            Assert.Equal(Visibility.Visible, window.ClassRow.Visibility);
            var choices = window.CourseFilter.Items.Cast<CourseChoice>().Select(choice => choice.Label).ToArray();
            Assert.Equal(["All classes", "CHM110", "CHM110L", "ENGL102", "ETH110", "MATH143", "NUTR150"], choices);

            window.CourseFilter.SelectedIndex = 5;
            UiFixture.Settle();
            Assert.Equal(["Next week (2)", "pset6", "midterm"], Rows(window));

            window.ShowSection("life");
            UiFixture.Settle();
            Assert.Equal(Visibility.Collapsed, window.ClassRow.Visibility);
            Assert.Equal("5 reminders", window.CountText.Text);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void CalendarShowsOneDayAndFindTakesOver() => ui.Run(() =>
    {
        var window = UiFixture.Open(Sample.Store());
        try
        {
            window.ShowSection("all");
            window.SetCalendarMode(true);
            UiFixture.Settle();
            Assert.Equal(Visibility.Visible, window.CalendarPanel.Visibility);
            Assert.Equal("October 2026", window.MonthHeading.Text);
            Assert.Equal(["Thursday, October 1 (2)", "dentist", "lab5"], Rows(window));
            var cells = window.MonthDays.Items.Cast<DayCell>().ToList();
            Assert.Equal(42, cells.Count);
            // October 2026 starts on a Thursday, the fourth column of a week that starts on Monday.
            Assert.Equal(3, cells.FindIndex(cell => cell.IsDay));
            Assert.Equal(new DateTime(2026, 10, 4), cells[6].Date);
            Assert.Equal(new DateTime(2026, 10, 5), cells[7].Date);
            var today = cells.Single(cell => cell.IsToday);
            Assert.True(today.IsSelected);
            Assert.Equal(2, today.Dots.Count);
            Assert.True(cells.Single(cell => cell.Date?.Day == 8).HasAssessment);

            window.FindBox.Text = "quiz";
            UiFixture.Settle();
            Assert.Equal(Visibility.Collapsed, window.CalendarPanel.Visibility);
            Assert.Equal(["Tomorrow (1)", "quiz4"], Rows(window));
            Assert.False(window.CalendarModeButton.IsEnabled);

            window.FindBox.Text = "";
            UiFixture.Settle();
            Assert.Equal(Visibility.Visible, window.CalendarPanel.Visibility);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void TheListIsLeftAloneWhileNothingChanges() => ui.Run(() =>
    {
        var now = Sample.Now;
        var window = UiFixture.Open(Sample.Store(), () => now);
        try
        {
            var before = window.ReminderList.ItemsSource;
            var dentist = window.ReminderList.Items.OfType<ReminderRow>().First();
            Assert.Equal("in 2 h", dentist.SecondaryWhen);

            now = now.AddMinutes(60);
            window.Render();
            Assert.Same(before, window.ReminderList.ItemsSource);
            Assert.Equal("in 85 min", dentist.SecondaryWhen);

            // Once its time has passed, a reminder moves to Archived.
            now = Sample.Local(2026, 10, 1, 16, 31);
            window.Render();
            UiFixture.Settle();
            Assert.NotSame(before, window.ReminderList.ItemsSource);
            Assert.Equal("Life (4)", window.LifeTab.Content);
            Assert.DoesNotContain("dentist", Rows(window));
        }
        finally { window.Close(); }
    });

    [Fact]
    public void ABirthdayStaysListedUntilTheDayAfter() => ui.Run(() =>
    {
        // 9 AM today, and the clock says 2:05 PM: an ordinary reminder is archived, the birthday is not.
        var birthday = Sample.Plain("megan", "Megan's birthday", Sample.Local(2026, 10, 1, 9, 0));
        var call = Sample.Plain("call", "Morning call", Sample.Local(2026, 10, 1, 9, 0));
        var now = Sample.Now;
        var window = UiFixture.Open(Sample.Store([birthday, call]), () => now);
        try
        {
            Assert.Equal("Life (1)", window.LifeTab.Content);
            Assert.Equal(["Today (1)", "megan"], Rows(window));
            var row = window.ReminderList.Items.OfType<ReminderRow>().Single();
            Assert.Equal("9:00 AM", row.PrimaryWhen);
            // Not "5 h ago": it is still today's.
            Assert.Equal("", row.SecondaryWhen);

            now = Sample.Local(2026, 10, 1, 23, 59);
            window.Render();
            Assert.Equal("Life (1)", window.LifeTab.Content);

            now = Sample.Local(2026, 10, 2, 0, 0).AddSeconds(30);
            window.Render();
            UiFixture.Settle();
            Assert.Equal("Life (0)", window.LifeTab.Content);
            window.ShowSection("archived");
            UiFixture.Settle();
            Assert.Equal(["Yesterday (2)", "megan", "call"], Rows(window));
        }
        finally { window.Close(); }
    });

    [Fact]
    public void OpeningAReminderFindsItWhateverWasShowing() => ui.Run(() =>
    {
        var window = UiFixture.Open(Sample.Store());
        try
        {
            window.FindBox.Text = "rent";
            UiFixture.Settle();
            window.OpenReminder("midterm");
            UiFixture.Settle();
            Assert.Equal("", window.FindBox.Text);
            Assert.True(window.AllTab.IsChecked);
            Assert.Equal("midterm", (window.ReminderList.SelectedItem as ReminderRow)?.Event.Id);

            window.OpenReminder("quiz3");
            UiFixture.Settle();
            Assert.True(window.ArchivedTab.IsChecked);
            Assert.Equal("quiz3", (window.ReminderList.SelectedItem as ReminderRow)?.Event.Id);
        }
        finally { window.Close(); }
    });

    [Fact]
    public void RemindersComeDueOnceAtTheirMovedTimes() => ui.Run(() =>
    {
        var window = UiFixture.Open(Sample.Store());
        try
        {
            string[] Due(DateTimeOffset now) => window.TakeDueReminders(now).Select(due => $"{due.Event.Id}:{due.Kind}").Order().ToArray();

            // Nothing is due at a time no alert was planned for.
            Assert.Empty(Due(Sample.Local(2026, 10, 1, 14, 5)));
            // The day-before notice of the quiz due tomorrow at 11:59 PM comes at 8 PM, not at 11:59 PM.
            Assert.Equal(["quiz4:offset_1440"], Due(Sample.Local(2026, 10, 1, 20, 0)));
            // Shown once: the next check half a minute later has nothing new.
            Assert.Empty(Due(Sample.Local(2026, 10, 1, 20, 0).AddSeconds(30)));
            Assert.Empty(Due(Sample.Local(2026, 10, 1, 23, 59)));
            // A daytime alert at the event time fires as the event starts.
            Assert.Equal(["rent:offset_0"], Due(Sample.Local(2026, 10, 2, 9, 0).AddSeconds(40)));
            // On the quiz's last evening its 2 hour, 1 hour and at-the-deadline alerts are one "tonight".
            Assert.Equal(["discussion:offset_1440", "quiz4:tonight"], Due(Sample.Local(2026, 10, 2, 20, 0)));
            Assert.Empty(Due(Sample.Local(2026, 10, 2, 21, 59)));
            Assert.Empty(Due(Sample.Local(2026, 10, 2, 23, 59)));
            // The 6:15 AM flight: the day-before notice waits for 8 AM, the 3:15 AM wake-up alert stays.
            Assert.Equal(["flight:offset_1440"], Due(Sample.Local(2026, 10, 4, 8, 1)));
            Assert.Equal(["flight:offset_180"], Due(Sample.Local(2026, 10, 5, 3, 15)));
        }
        finally { window.Close(); }
    });

    [Fact]
    public void FiredKeysOfMinuteOffsetsMatchEarlierVersions()
    {
        var item = new CalendarEvent { Id = "abc", StartAt = "2026-10-02T05:59:00Z" };
        Assert.Equal("abc:2026-10-02T05:59:00Z:1440", MainWindow.FiredKey(item, "offset_1440"));
        Assert.Equal("abc:2026-10-02T05:59:00Z:tonight", MainWindow.FiredKey(item, ReminderSchedule.KindTonight));
    }

    [Fact]
    public void ReopensWhereItWasClosedUnlessThatIsOffScreen() => ui.Run(() =>
    {
        // Never shown: only where the window would open is looked at.
        var view = new ViewSettings { WindowLeft = 120, WindowTop = 90, WindowWidth = 860, WindowHeight = 600 };
        var window = new MainWindow(Sample.Store(view: view), background: false, () => Sample.Now, _ => new FakeBridge());
        Assert.Equal(WindowStartupLocation.Manual, window.WindowStartupLocation);
        Assert.Equal(120, window.Left);
        Assert.Equal(90, window.Top);
        Assert.Equal(860, window.Width);
        Assert.Equal(600, window.Height);
        window.Close();

        // A screen that is no longer there: the size is kept, the place is not.
        view.WindowLeft = 60000;
        window = new MainWindow(Sample.Store(view: view), background: false, () => Sample.Now, _ => new FakeBridge());
        Assert.Equal(WindowStartupLocation.CenterScreen, window.WindowStartupLocation);
        Assert.Equal(860, window.Width);
        window.Close();
    });

    [Fact]
    public void TheAppIsShownAsAIPlannerWhileItsFilesKeepTheirName() => ui.Run(() =>
    {
        var window = UiFixture.Open(Sample.Store());
        var about = new AboutWindow([]);
        try
        {
            Assert.Equal("AI Planner", window.Title);
            Assert.Equal("About AI Planner", about.Title);
            var assembly = typeof(MainWindow).Assembly;
            Assert.Equal("AI Planner", assembly.GetCustomAttributes(typeof(System.Reflection.AssemblyProductAttribute), false)
                .OfType<System.Reflection.AssemblyProductAttribute>().Single().Product);
            // The exe (and so the process name), and the settings folder, keep the old name on purpose.
            Assert.Equal("AI Calendar", assembly.GetName().Name);
        }
        finally
        {
            about.Close();
            window.Close();
        }
    });

    [Fact]
    public void UnpairedAndEmptyOffersToPair() => ui.Run(() =>
    {
        var window = UiFixture.Open(Sample.Store(events: [], paired: false));
        try
        {
            Assert.Equal(Visibility.Visible, window.ConnectBar.Visibility);
            Assert.Equal("Cloud: not connected", window.CloudText.Text);
            Assert.False(window.SyncButton.IsEnabled);
            Assert.Equal(Visibility.Visible, window.EmptyPanel.Visibility);
            Assert.Equal("Pair this PC...", window.EmptyLink.Content);
        }
        finally { window.Close(); }
    });
}
