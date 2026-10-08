# NOT ROUTINE: generates a NEW pairing code, stores it as the Firebase secret, redeploys `api`, then
# pairs this PC. Every other device keeps working, but the old code stops working for new pairings.
param(
    [Parameter(Mandatory = $true)][string]$Project,
    [string]$ServerUrl = "https://us-central1-$Project.cloudfunctions.net/api"
)
$ErrorActionPreference = 'Stop'

$project = $Project
$serverUrl = $ServerUrl.TrimEnd('/')
$firebase = Join-Path $PSScriptRoot '..\bridge\node_modules\firebase-tools\lib\bin\firebase.js'
# Must match SettingsStore: %USERPROFILE%\.ai-calendar-desktop (the old %LOCALAPPDATA% path is only a legacy migration source).
$settingsPath = Join-Path $env:USERPROFILE '.ai-calendar-desktop\settings.json'
$temporarySecret = Join-Path $env:TEMP ("ai-calendar-pairing-{0}.txt" -f [Guid]::NewGuid().ToString('N'))
$secret = $null
$temporarySettings = $null

if (Get-Process -Name 'AI Calendar' -ErrorAction SilentlyContinue) {
    throw 'Close AI Calendar before re-pairing; the running app would overwrite the new pairing on its next save.'
}

try {
    $bytes = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
    $secret = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    [IO.File]::WriteAllText($temporarySecret, $secret, [Text.UTF8Encoding]::new($false))

    & node $firebase functions:secrets:set AI_CALENDAR_PAIRING_CODE `
        --project $project --data-file $temporarySecret --force
    if ($LASTEXITCODE -ne 0) { throw 'Pairing-secret deployment failed.' }
    & node $firebase deploy --only functions:api --project $project
    if ($LASTEXITCODE -ne 0) { throw 'API deployment after pairing-secret rotation failed.' }

    if (Test-Path -LiteralPath $settingsPath) {
        $settings = Get-Content -LiteralPath $settingsPath -Raw | ConvertFrom-Json
    } else {
        $settings = [pscustomobject]@{
            ServerUrl = $serverUrl
            ClientId = "windows-$([Guid]::NewGuid().ToString('N'))"
            ProtectedDeviceToken = ''
            FiredNotifications = @()
        }
    }
    $settings.ServerUrl = $serverUrl
    $request = @{
        pairingCode = $secret
        installationId = $settings.ClientId
        deviceName = "Windows desktop ($env:COMPUTERNAME)"
        pushEnabled = $false
    } | ConvertTo-Json
    $response = Invoke-RestMethod -Method Post -Uri "$serverUrl/v1/devices/register" `
        -ContentType 'application/json' -Body $request
    if (-not $response.deviceToken) { throw 'Pairing returned no desktop token.' }

    $encrypted = [Security.Cryptography.ProtectedData]::Protect(
        [Text.Encoding]::UTF8.GetBytes([string]$response.deviceToken),
        $null,
        [Security.Cryptography.DataProtectionScope]::CurrentUser
    )
    $settings.ProtectedDeviceToken = [Convert]::ToBase64String($encrypted)
    $folder = Split-Path $settingsPath
    New-Item -ItemType Directory -Path $folder -Force | Out-Null
    # Atomic write with a backup, matching the app's own SettingsStore.Save.
    $temporarySettings = "$settingsPath.$([Guid]::NewGuid().ToString('N')).tmp"
    [IO.File]::WriteAllText($temporarySettings, ($settings | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
    if (Test-Path -LiteralPath $settingsPath) {
        [IO.File]::Replace($temporarySettings, $settingsPath, "$settingsPath.bak")
    } else {
        [IO.File]::Move($temporarySettings, $settingsPath)
    }
    Copy-Item -LiteralPath $settingsPath -Destination "$settingsPath.bak" -Force
    $response = $null
    Write-Output 'Desktop pairing completed.'
} finally {
    if (Test-Path -LiteralPath $temporarySecret) { Remove-Item -LiteralPath $temporarySecret -Force }
    if ($temporarySettings -and (Test-Path -LiteralPath $temporarySettings)) { Remove-Item -LiteralPath $temporarySettings -Force }
    $secret = $null
}
