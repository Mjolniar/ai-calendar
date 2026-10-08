# Usage: mcp-call.ps1 <toolName> '<json arguments>'   (calls the AI Calendar MCP endpoint with the local API token)
param([string]$Tool, [string]$ArgsJson = '{}', [string]$BaseUrl = $env:AI_CALENDAR_BASE_URL)
if (-not $BaseUrl) { throw 'Set AI_CALENDAR_BASE_URL (https://us-central1-<project-id>.cloudfunctions.net/api) or pass -BaseUrl.' }
$root = Split-Path -Parent $PSScriptRoot
$t = ([IO.File]::ReadAllText((Join-Path $root 'bridge\.env.api-token'))).Trim()
$body = '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"' + $Tool + '","arguments":' + $ArgsJson + '}}'
$r = Invoke-WebRequest "$($BaseUrl.TrimEnd('/'))/mcp" -Method Post -UseBasicParsing -ContentType 'application/json' -Headers @{ Authorization = "Bearer $t"; Accept = 'application/json, text/event-stream' } -Body ([Text.Encoding]::UTF8.GetBytes($body))
$line = ($r.Content -split "`n" | Where-Object { $_ -like 'data:*' } | Select-Object -First 1) -replace '^data: ', ''
$res = ($line | ConvertFrom-Json).result
if ($res.structuredContent.content -ne $null) { $res.structuredContent.content } else { $res.content[0].text }
