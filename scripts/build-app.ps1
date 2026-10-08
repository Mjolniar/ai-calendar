param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Tasks = @('assembleDebug'))
$repo = Split-Path -Parent $PSScriptRoot
$out = Join-Path $repo 'build\scripts'
New-Item -ItemType Directory -Path $out -Force | Out-Null
# Runs the Gradle wrapper and prints a compact result. Keep the repository in a path without '&'.
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
# A %TEMP% with an apostrophe or a space can break the JVM's loopback pipe; use a plain folder.
$env:TEMP = (Join-Path $repo 'build\tmp')
$env:TMP = (Join-Path $repo 'build\tmp')
Set-Location $repo
$log = (Join-Path $out 'gradle.log')
$sw = [System.Diagnostics.Stopwatch]::StartNew()
# Started on its own, not through a pipe of this script: when the build has to start a new Gradle
# daemon, the daemon would inherit that pipe and keep this script waiting until the daemon exits
# (three hours). Only the wrapper process is waited for.
$gradle = Start-Process -FilePath 'cmd.exe' -WorkingDirectory $repo -WindowStyle Hidden -PassThru `
    -ArgumentList '/c', "gradlew.bat $($Tasks -join ' ') --console=plain > `"$log`" 2>&1"
$gradle.WaitForExit()
$code = $gradle.ExitCode
"EXIT $code in $([int]$sw.Elapsed.TotalSeconds)s"
Select-String -Path $log -Pattern '^e: |^w: |error:|FAILED|BUILD |tests completed|Lint found|Wrote HTML' |
    Select-Object -First 80 | ForEach-Object { $_.Line -replace 'file:///.*/app/src/main/java/com/example/aicalendar/', '' }
