$repo = Split-Path -Parent $PSScriptRoot
$out = Join-Path $repo 'build\scripts'
New-Item -ItemType Directory -Path $out -Force | Out-Null
# Deploys the bridge's Cloud Functions (api and canvasSync) from the junction path, which has no
# "&" in it, calling firebase-tools through node rather than its shim. Output goes to
# bridge-deploy.log; the last line is DEPLOY_OK or DEPLOY_FAILED.
$ErrorActionPreference = 'Continue'
Set-Location $repo
$log = (Join-Path $out 'bridge-deploy.log')
"==== $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ====" | Out-File -Encoding utf8 $log
& node 'bridge\node_modules\firebase-tools\lib\bin\firebase.js' deploy --only functions --non-interactive 2>&1 |
    ForEach-Object { ("$_" -replace '\x1b\[[0-9;]*m', '') } | Out-File -Encoding utf8 -Append $log
if ($LASTEXITCODE -eq 0) { 'DEPLOY_OK' | Out-File -Encoding utf8 -Append $log } else { "DEPLOY_FAILED exit=$LASTEXITCODE" | Out-File -Encoding utf8 -Append $log }
