param([switch]$NoLaunch)

$ErrorActionPreference = 'Stop'
$project = Join-Path $PSScriptRoot 'AI.Calendar.Desktop.csproj'
$publish = Join-Path $PSScriptRoot 'publish'
# The app is shown as "AI Planner" (since 1.5.1). The exe, this folder, the settings folder and the
# process name stay "AI Calendar" on purpose: a taskbar pin, the pairing and the checks below rely on them.
$install = Join-Path $env:LOCALAPPDATA 'Programs\AI Calendar Desktop'
$desktop = [Environment]::GetFolderPath('Desktop')
$shortcutName = 'AI Planner.lnk'
$oldShortcutName = 'AI Calendar.lnk'

$installedExe = Join-Path $install 'AI Calendar.exe'
$running = Get-Process -Name 'AI Calendar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Path -and ($_.Path -ieq $installedExe) }
if ($running) { throw 'AI Planner is running. Quit it (File > Exit, or right-click its icon in the notification area and choose Exit), then run install.ps1 again.' }

dotnet publish $project -c Release -r win-x64 --self-contained true `
    -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o $publish
if ($LASTEXITCODE -ne 0) { throw 'Desktop publish failed.' }

New-Item -ItemType Directory -Path $install -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $publish 'AI Calendar.exe') -Destination (Join-Path $install 'AI Calendar.exe') -Force

# Earlier installs gave shortcuts their own icon files in this folder, and a taskbar pin or a
# launcher shortcut may still point at one. Keep each of them in step with the app's icon, so no
# shortcut goes on showing old artwork.
$appIcon = Join-Path $PSScriptRoot 'AppIcon.ico'
Get-ChildItem -LiteralPath $install -Filter '*.ico' -File | ForEach-Object {
    Copy-Item -LiteralPath $appIcon -Destination $_.FullName -Force
}

$shell = New-Object -ComObject WScript.Shell
function Set-AppShortcut([string]$path) {
    $shortcut = $shell.CreateShortcut($path)
    $shortcut.TargetPath = Join-Path $install 'AI Calendar.exe'
    $shortcut.WorkingDirectory = $install
    $shortcut.IconLocation = "$(Join-Path $install 'AI Calendar.exe'),0"
    $shortcut.Description = 'AI Planner desktop companion'
    $shortcut.Save()
}

# Only a shortcut that starts this app is ours to replace; another program's "AI Calendar.lnk" is left alone.
function Test-AppShortcut([string]$path) {
    try { return $shell.CreateShortcut($path).TargetPath -ieq $installedExe }
    catch { return $false }
}

# Desktop: the shortcut under the new name; the one under the old name goes.
Set-AppShortcut (Join-Path $desktop $shortcutName)
$oldShortcut = Join-Path $desktop $oldShortcutName
if ((Test-Path -LiteralPath $oldShortcut) -and (Test-AppShortcut $oldShortcut)) { Remove-Item -LiteralPath $oldShortcut -Force }

# Start Menu: wherever there is a shortcut to this app under the old name, the same under the new name instead.
# (Taskbar pins live elsewhere and are left alone; they point at the exe, which keeps its name.)
$startMenu = [Environment]::GetFolderPath('Programs')
if ($startMenu -and (Test-Path -LiteralPath $startMenu)) {
    Get-ChildItem -LiteralPath $startMenu -Filter $oldShortcutName -File -Recurse -ErrorAction SilentlyContinue |
        Where-Object { Test-AppShortcut $_.FullName } | ForEach-Object {
            Set-AppShortcut (Join-Path $_.DirectoryName $shortcutName)
            Remove-Item -LiteralPath $_.FullName -Force
        }
}

# Explorer keeps icons in a cache; tell it they changed, or the taskbar and desktop show the old ones.
Add-Type -Namespace AICalendarInstall -Name Shell -MemberDefinition @'
[DllImport("shell32.dll")]
public static extern void SHChangeNotify(int eventId, uint flags, IntPtr item1, IntPtr item2);
'@
[AICalendarInstall.Shell]::SHChangeNotify(0x08000000, 0, [IntPtr]::Zero, [IntPtr]::Zero)

if (-not $NoLaunch) { Start-Process -FilePath (Join-Path $install 'AI Calendar.exe') }
