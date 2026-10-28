param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArgs = @('testDebugUnitTest'))
# Runs the headless UI harness with the project's Gradle wrapper and prints a short result.
# Same environment as scripts\build-app.ps1: a repository path without '&', Android Studio's
# JDK, and a plain TEMP folder (the default one has an apostrophe in it, which breaks the JVM).
#
#   run.ps1                        all UI tests and pictures
#   run.ps1 testDebugUnitTest --tests "*DeliveryFlows*"
#
# Pictures land in build\shots (light) and build\shots-dark. The full Gradle output is run.log.
$harness = $PSScriptRoot
$repo = Split-Path -Parent $harness
if (-not (Test-Path -LiteralPath (Join-Path $harness 'build.gradle.kts'))) { "not found: $harness"; exit 2 }
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$tmp = Join-Path $repo 'build\tmp'
New-Item -ItemType Directory -Path $tmp -Force | Out-Null
$env:TEMP = $tmp
$env:TMP = $tmp
# Robolectric cannot load its runtime jar from a folder with a space in its path (the user folder
# here). Copy the jars it has downloaded into a plain folder; build.gradle.kts points it there.
$plainDeps = Join-Path $repo 'build\robolectric-deps'
$m2 = Join-Path $env:USERPROFILE '.m2\repository\org\robolectric\android-all-instrumented'
if (Test-Path -LiteralPath $m2) {
    New-Item -ItemType Directory -Path $plainDeps -Force | Out-Null
    Get-ChildItem -LiteralPath $m2 -Recurse -Filter 'android-all-instrumented-*.jar' | ForEach-Object {
        $target = Join-Path $plainDeps $_.Name
        if (-not (Test-Path -LiteralPath $target)) { Copy-Item -LiteralPath $_.FullName -Destination $target }
    }
}
$log = Join-Path $harness 'run.log'
$sw = [System.Diagnostics.Stopwatch]::StartNew()
# Started on its own with its output in a file: a Gradle daemon started by this build would
# otherwise inherit the caller's pipe and keep the caller waiting until the daemon exits.
$gradle = Start-Process -FilePath 'cmd.exe' -WorkingDirectory $repo -WindowStyle Hidden -PassThru `
    -ArgumentList '/c', "gradlew.bat -p ui-harness $($GradleArgs -join ' ') --console=plain > `"$log`" 2>&1"
$gradle.WaitForExit()
$code = $gradle.ExitCode
"EXIT $code in $([int]$sw.Elapsed.TotalSeconds)s"
Select-String -Path $log -Pattern '^e: |error:|FAILED|BUILD |tests completed|What went wrong|Could not|No cached version' |
    Select-Object -First 60 | ForEach-Object { $_.Line }

$results = Join-Path $harness 'build\test-results\testDebugUnitTest'
if ($code -eq 0 -and (Test-Path -LiteralPath $results)) {
    $tests = 0; $failed = 0
    Get-ChildItem -LiteralPath $results -Filter 'TEST-*.xml' | ForEach-Object {
        $suite = ([xml](Get-Content -LiteralPath $_.FullName -Raw)).testsuite
        $tests += [int]$suite.tests
        $failed += [int]$suite.failures + [int]$suite.errors
    }
    "tests: $tests, failed: $failed"
    $light = @(Get-ChildItem -LiteralPath (Join-Path $harness 'build\shots') -Filter '*.png' -ErrorAction SilentlyContinue).Count
    $dark = @(Get-ChildItem -LiteralPath (Join-Path $harness 'build\shots-dark') -Filter '*.png' -ErrorAction SilentlyContinue).Count
    "pictures: $light light, $dark dark"
}
exit $code
