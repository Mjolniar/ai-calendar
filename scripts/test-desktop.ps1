param([string]$Filter = '')
$repo = Split-Path -Parent $PSScriptRoot
$out = Join-Path $repo 'build\scripts'
New-Item -ItemType Directory -Path $out -Force | Out-Null
# Builds the desktop tests, runs them, and writes a short summary to desktop-test-summary.txt:
# each distinct failure message once (with the number of tests that hit it and the first few test
# names), then the totals. The full output is desktop-test.log; the pictures the tests drew are
# zipped into desktop-shots.zip.
$ErrorActionPreference = 'Continue'
Set-Location (Join-Path $repo 'desktop')
$log = (Join-Path $out 'desktop-test.log')
$summary = (Join-Path $out 'desktop-test-summary.txt')
$shots = (Join-Path $out 'desktop-shots.zip')
$lines = @()
$build = dotnet build tests\AI.Calendar.Desktop.Tests.csproj -v q -nologo 2>&1
if ($LASTEXITCODE -ne 0) {
    $lines += 'BUILD FAILED'
    $lines += ($build | Where-Object { "$_" -match 'error|warn' } | Select-Object -First 30 | ForEach-Object { "$_" })
} else {
    Remove-Item 'tests\artifacts\*.png' -Force -ErrorAction SilentlyContinue
    $testArgs = @('test', 'tests\AI.Calendar.Desktop.Tests.csproj', '--no-build', '-nologo', '--logger', 'console;verbosity=normal')
    if ($Filter) { $testArgs += @('--filter', $Filter) }
    & dotnet @testArgs 2>&1 | ForEach-Object { "$_" } | Out-File -Encoding utf8 $log
    $text = @(Get-Content $log)
    $failures = [ordered]@{}
    $current = ''
    for ($i = 0; $i -lt $text.Count; $i++) {
        $line = $text[$i]
        if ($line -match '^\s+Failed (\S+)') { $current = $Matches[1] }
        if ($line -match '^\s+Error Message:') {
            $last = [Math]::Min($i + 4, $text.Count - 1)
            $message = (($text[($i + 1)..$last] | Where-Object { $_ -notmatch 'Stack Trace' } | ForEach-Object { $_.Trim() }) -join ' | ')
            if ($message.Length -gt 600) { $message = $message.Substring(0, 600) + '...' }
            if (-not $failures.Contains($message)) { $failures[$message] = @() }
            $failures[$message] += $current
        }
        if ($line -match '^(Passed!|Failed!|Total tests|\s+Passed:|\s+Failed:|Test Run)') { $lines += $line }
    }
    foreach ($message in $failures.Keys) {
        $names = $failures[$message]
        $lines += "FAILED x$($names.Count): " + (($names | Select-Object -First 4 | ForEach-Object { ($_ -split '\.')[-1] }) -join ', ')
        $lines += '    ' + $message
    }
    Remove-Item $shots -Force -ErrorAction SilentlyContinue
    if (Test-Path 'tests\artifacts\*.png') { Compress-Archive -Path 'tests\artifacts\*.png' -DestinationPath $shots -Force }
}
$lines | ForEach-Object { $_ -replace '[<>]', '|' } | Set-Content -Encoding utf8 $summary
"lines: $($lines.Count)"
