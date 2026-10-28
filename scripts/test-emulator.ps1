# Runs the bridge's emulator tests: every MCP tool over HTTP, and the Firestore store, against the
# Firestore emulator for a demo project (it cannot reach the real one). Needs Java 11+; uses
# Android Studio's JDK. The first run downloads the emulator (about 60 MB).
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-emulator.ps1
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
# A %TEMP% with an apostrophe or a space can break the JVM's loopback pipe.
$env:TEMP = (Join-Path $repo 'build\tmp')
$env:TMP = (Join-Path $repo 'build\tmp')
New-Item -ItemType Directory -Path $env:TEMP -Force | Out-Null
Set-Location $repo
& node 'bridge\node_modules\firebase-tools\lib\bin\firebase.js' emulators:exec --only firestore --project demo-ai-calendar `
    'node bridge/node_modules/vitest/vitest.mjs run --root bridge --config vitest.emulator.config.ts'
$code = $LASTEXITCODE
Remove-Item -LiteralPath (Join-Path $repo 'firestore-debug.log') -ErrorAction SilentlyContinue
exit $code
