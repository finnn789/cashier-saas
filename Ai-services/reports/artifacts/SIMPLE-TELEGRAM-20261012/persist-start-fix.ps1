$ErrorActionPreference = 'Stop'
$memoryPath = 'E:\Java\cashier-saas\.ai\MEMORY.md'
$tasksPath = 'E:\Java\cashier-saas\.ai\TASKS.md'
$memoryText = [IO.File]::ReadAllText($memoryPath)
$tasksText = [IO.File]::ReadAllText($tasksPath)
if ($memoryText -notmatch '(?m)^Memory version: 24\r?$' -or $tasksText -notmatch '(?m)^Memory version: 24\r?$') {
    throw 'Canonical memory changed; review before persistence.'
}
$memoryText = $memoryText -replace '(?m)^Memory version: 24\r?$', 'Memory version: 25'
$tasksText = $tasksText -replace '(?m)^Memory version: 24\r?$', 'Memory version: 25'
$entry = '- TELEGRAM-START-20261012 config fix: user /start report traced to literal example-only allowlist123456789 in real .env; ready endpoint observed status ready/mode telegram-polling. Only placeholder allowlist changed to []; credentials and other config retained. Restart existing process/F5 required before effective; actual /start reply after restart unverified. No code/tests/startup/provider/Telegram API calls. Evidence: Ai-services/reports/TELEGRAM-START-20261012.md. UNIT remains paused.'
$marker = '## Evidence-qualified state'
$position = $memoryText.IndexOf($marker)
if ($position -lt 0) { throw 'Memory marker missing.' }
$position += $marker.Length
$memoryText = $memoryText.Substring(0,$position) + "`r`n`r`n" + $entry + $memoryText.Substring($position)
$marker = 'Memory version: 25'
$position = $tasksText.IndexOf($marker) + $marker.Length
$tasksText = $tasksText.Substring(0,$position) + "`r`n`r`n" + $entry + $tasksText.Substring($position)
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($memoryPath,$memoryText,$utf8NoBom)
[IO.File]::WriteAllText($tasksPath,$tasksText,$utf8NoBom)
Write-Output 'Telegram /start configuration fix persisted in memory25 and tasks.'
