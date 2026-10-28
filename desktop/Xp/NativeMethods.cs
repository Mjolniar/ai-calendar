using System.Runtime.InteropServices;

namespace AICalendar.Desktop.Xp;

/// <summary>The few Win32 calls the theme needs and WPF does not expose.</summary>
internal static class NativeMethods
{
    private const int GwlExStyle = -20;
    private const long WsExToolWindow = 0x00000080;
    private const long WsExNoActivate = 0x08000000;

    private const uint SndAsync = 0x0001;
    private const uint SndNoDefault = 0x0002;
    private const uint SndAlias = 0x00010000;
    private const uint SndSystem = 0x00200000;
    private const uint MbIconAsterisk = 0x00000040;

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetForegroundWindow(IntPtr window);

    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW")]
    private static extern IntPtr GetWindowLongPtr(IntPtr window, int index);

    [DllImport("user32.dll", EntryPoint = "SetWindowLongPtrW")]
    private static extern IntPtr SetWindowLongPtr(IntPtr window, int index, IntPtr value);

    [DllImport("winmm.dll", EntryPoint = "PlaySoundW", CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool PlaySound(string sound, IntPtr module, uint flags);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool MessageBeep(uint type);

    /// <summary>
    /// Makes a window the foreground window. A popup menu opened from the notification area needs
    /// this, or it stays open when the user clicks somewhere else.
    /// </summary>
    public static void BringToForeground(IntPtr window) => SetForegroundWindow(window);

    /// <summary>Keeps a popup out of Alt+Tab and stops it from taking the keyboard focus when clicked.</summary>
    public static void MakeToolWindowWithoutActivation(IntPtr window)
    {
        var style = GetWindowLongPtr(window, GwlExStyle).ToInt64();
        SetWindowLongPtr(window, GwlExStyle, new IntPtr(style | WsExToolWindow | WsExNoActivate));
    }

    /// <summary>
    /// Plays the sound Windows uses for calendar reminders, as set under Sound settings (so a muted
    /// scheme stays muted). Falls back to the standard information sound.
    /// </summary>
    public static void PlayReminderSound()
    {
        try
        {
            if (!PlaySound("Notification.Reminder", IntPtr.Zero, SndAlias | SndAsync | SndNoDefault | SndSystem))
                MessageBeep(MbIconAsterisk);
        }
        catch (Exception exception) when (exception is DllNotFoundException or EntryPointNotFoundException) { }
    }
}
