# Publishes the phone app over the cloud: builds the debug APK (unless -SkipBuild), copies it to
# bridge\release\ with release.json (version, size, SHA-256), and deploys the `api` function. Paired
# phones then offer "Update available" (at most every 6 hours, or Help > Check for Updates).
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/publish-app.ps1
#
# Bump versionCode in app\build.gradle.kts first: a phone only offers a build with a higher one.
# Log: build\scripts\publish-app.log, last line PUBLISH_OK or PUBLISH_FAILED.
param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$log = Join-Path $root 'build\scripts\publish-app.log'
New-Item -ItemType Directory -Path (Split-Path $log) -Force | Out-Null
"==== $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ====" | Out-File -Encoding utf8 $log
function Say([string]$text) { $text; $text | Out-File -Encoding utf8 -Append $log }
try {
    # Claude Code shells set this, and cmd then cannot find gradlew.bat in the current folder.
    $env:NoDefaultCurrentDirectoryInExePath = $null
    if (-not $SkipBuild) {
        $build = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'scripts\build-app.ps1') ':app:assembleDebug'
        $build | Out-File -Encoding utf8 -Append $log
        if (-not ($build -match '^EXIT 0 ')) { throw 'The build failed; see build\scripts\gradle.log.' }
    }
    $gradle = Get-Content -Raw (Join-Path $root 'app\build.gradle.kts')
    $code = [int]([regex]::Match($gradle, 'versionCode\s*=\s*(\d+)').Groups[1].Value)
    $name = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
    if ($code -le 0 -or -not $name) { throw 'Could not read versionCode and versionName from app\build.gradle.kts.' }

    $apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path -LiteralPath $apk)) { throw "No APK at $apk" }
    $release = Join-Path $root 'bridge\release'
    New-Item -ItemType Directory -Path $release -Force | Out-Null
    Copy-Item -LiteralPath $apk -Destination (Join-Path $release 'app.apk') -Force
    $file = Get-Item -LiteralPath (Join-Path $release 'app.apk')
    $sha = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToLowerInvariant()
    $manifest = [ordered]@{
        versionCode = $code
        versionName = $name
        sha256 = $sha
        size = $file.Length
        publishedAt = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    } | ConvertTo-Json
    [IO.File]::WriteAllText((Join-Path $release 'release.json'), $manifest, [Text.UTF8Encoding]::new($false))
    Say "Release $name (versionCode $code), $([math]::Round($file.Length / 1MB, 1)) MB, sha256 $sha"

    Set-Location $root
    $ErrorActionPreference = 'Continue'
    & node 'bridge\node_modules\firebase-tools\lib\bin\firebase.js' deploy --only functions:api --non-interactive 2>&1 |
        ForEach-Object { ("$_" -replace '\x1b\[[0-9;]*m', '') } | Out-File -Encoding utf8 -Append $log
    if ($LASTEXITCODE -ne 0) { throw "Deploy failed (exit $LASTEXITCODE); see the log." }
    Say 'PUBLISH_OK'
} catch {
    Say "PUBLISH_FAILED: $($_.Exception.Message)"
    exit 1
}
