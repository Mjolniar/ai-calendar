$repo = Split-Path -Parent $PSScriptRoot
$out = Join-Path $repo 'build\scripts'
New-Item -ItemType Directory -Path $out -Force | Out-Null
# Closes the installed AI Calendar, installs the current build and starts it again.
# Progress goes to desktop-install.log, which ends with INSTALL_OK or INSTALL_FAILED.
$ErrorActionPreference = 'Continue'
$log = (Join-Path $out 'desktop-install.log')
Remove-Item $log -Force -ErrorAction SilentlyContinue
function Note([string]$text) { "$(Get-Date -Format 'HH:mm:ss') $text" | Add-Content -Encoding utf8 $log }

$exe = Join-Path $env:LOCALAPPDATA 'Programs\AI Calendar Desktop\AI Calendar.exe'
function Running { @(Get-Process -Name 'AI Calendar' -ErrorAction SilentlyContinue | Where-Object { $_.Path -and ($_.Path -ieq $exe) }) }
$expected = @(([xml](Get-Content -LiteralPath (Join-Path $repo 'desktop\AI.Calendar.Desktop.csproj'))).Project.PropertyGroup | ForEach-Object { $_.Version } | Where-Object { $_ })[0]

foreach ($process in Running) {
    Note "closing pid $($process.Id)"
    $null = $process.CloseMainWindow()
}
for ($i = 0; $i -lt 16 -and (Running).Count -gt 0; $i++) { Start-Sleep -Milliseconds 500 }
foreach ($process in Running) {
    # Closing the window only hid it (it is kept in the notification area): end the process.
    Note "pid $($process.Id) is still running after its window closed; stopping it"
    Stop-Process -Id $process.Id -Force
}
Start-Sleep -Seconds 1

Note "installing $expected"
$output = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'desktop\install.ps1') -NoLaunch 2>&1
$code = $LASTEXITCODE
$output | ForEach-Object { "$_" } | Where-Object { $_ -match 'error|warn|->|failed|running|Exception' } | Select-Object -First 25 | ForEach-Object { Note ("  " + ($_ -replace '[<>]', '|')) }
$version = if (Test-Path -LiteralPath $exe) { (Get-Item -LiteralPath $exe).VersionInfo.ProductVersion } else { 'missing' }
Note "install exit $code, installed version $version"

Start-Process -FilePath $exe -WorkingDirectory (Split-Path $exe)
Note 'started'
if ($code -eq 0 -and $version -like "$expected*") { Note 'INSTALL_OK' } else { Note 'INSTALL_FAILED' }
