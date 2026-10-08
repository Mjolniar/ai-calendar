param([switch]$NoLaunch)

$ErrorActionPreference = 'Stop'
$project = Join-Path $PSScriptRoot 'AI.Calendar.Desktop.csproj'
$publish = Join-Path $PSScriptRoot 'publish'
$install = Join-Path $env:LOCALAPPDATA 'Programs\AI Calendar Desktop'
$desktop = [Environment]::GetFolderPath('Desktop')
$shortcutPath = Join-Path $desktop 'AI Calendar.lnk'

$installedExe = Join-Path $install 'AI Calendar.exe'
$running = Get-Process -Name 'AI Calendar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Path -and ($_.Path -ieq $installedExe) }
if ($running) { throw 'AI Calendar is running. Quit it (File > Exit, or right-click its icon in the notification area and choose Exit), then run install.ps1 again.' }

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
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = Join-Path $install 'AI Calendar.exe'
$shortcut.WorkingDirectory = $install
$shortcut.IconLocation = "$(Join-Path $install 'AI Calendar.exe'),0"
$shortcut.Description = 'AI Calendar desktop companion'
$shortcut.Save()

# Explorer keeps icons in a cache; tell it they changed, or the taskbar and desktop show the old ones.
Add-Type -Namespace AICalendarInstall -Name Shell -MemberDefinition @'
[DllImport("shell32.dll")]
public static extern void SHChangeNotify(int eventId, uint flags, IntPtr item1, IntPtr item2);
'@
[AICalendarInstall.Shell]::SHChangeNotify(0x08000000, 0, [IntPtr]::Zero, [IntPtr]::Zero)

if (-not $NoLaunch) { Start-Process -FilePath (Join-Path $install 'AI Calendar.exe') }
