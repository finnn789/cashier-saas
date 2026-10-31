$ErrorActionPreference = 'Stop'
$repositoryRoot = 'E:\Java\cashier-saas'
$memoryPath = Join-Path $repositoryRoot '.ai\MEMORY.md'
$tasksPath = Join-Path $repositoryRoot '.ai\TASKS.md'
$indexPath = Join-Path $repositoryRoot '.ai\context\INDEX.md'
$memoryText = [IO.File]::ReadAllText($memoryPath)
$tasksText = [IO.File]::ReadAllText($tasksPath)
$indexText = [IO.File]::ReadAllText($indexPath)
if ($memoryText -notmatch '(?m)^Memory version: 23\r?$' -or $tasksText -notmatch '(?m)^Memory version: 23\r?$') {
    throw 'Canonical memory changed; review current version before persistence.'
}
$reviewedEvidence = @'
- TASK-041 simple Telegram chatbot SOURCE-REVIEWED / READY FOR TESTING (2026-10-12): three required values TELEGRAM_BOT_TOKEN/OPENROUTER_API_KEY/AI_MODEL, uv run main.py + F5, general chat without required tenant/FAQ. Default local-demo tenant (including blank legacy value); optional allowlist empty accepts private non-bot users, populated remains strict. No business backend/data access. Existing FastAPI/LangGraph/client/serial poller retained; startup now fails clearly with sanitized messages, restores HTTP log suppression, cleans resources on cancellation. All22 Python files statically parse, launch JSON valid; actual same-thread worker events show9 reserved paths/zero violations, protected code/dependencies unchanged. External user .env change preserved. No tests/startup/provider/Telegram/DB calls; model/credentials operator-owned, runtime unverified. Evidence: Ai-services/reports/SIMPLE-TELEGRAM-20261012-orchestrator.md, reports/TASK-041.md, tasks/TASK-041.md. TASK-040 demo setup superseded; production integrations and prior UNIT pause preserved.

'@
$memoryText = $memoryText -replace '(?m)^Memory version: 23\r?$', 'Memory version: 24'
$memoryText = $memoryText -replace '(?m)^Updated: 2026-10-11 \(Asia/Jakarta\)\r?$', 'Updated: 2026-10-12 (Asia/Jakarta)'
$evidenceMarker = '## Evidence-qualified state'
$evidencePosition = $memoryText.IndexOf($evidenceMarker)
if ($evidencePosition -lt 0) { throw 'Memory evidence marker missing.' }
$evidenceEnd = $evidencePosition + $evidenceMarker.Length
$memoryText = $memoryText.Substring(0, $evidenceEnd) + "`r`n`r`n" + $reviewedEvidence + $memoryText.Substring($evidenceEnd)
$nextPosition = $memoryText.IndexOf('## Next action')
$unitPosition = $memoryText.IndexOf('Existing Cashier UNIT work remains independently paused:', $nextPosition)
if ($nextPosition -lt 0 -or $unitPosition -lt 0) { throw 'Memory next-action markers missing.' }
$nextAction = @'
## Next action

Simple Telegram AI chatbot TASK-041 READY FOR TESTING by source review. Fill the three operator-owned values in Ai-services/.env, then run uv run main.py or F5 (Telegram AI Chatbot). No mandatory tenant/FAQ; optional populated allowlist is still enforced. User confirmed model already available; no real provider/Telegram/runtime checks performed. Testing remains only on explicit user request. Production customer/auth/Spring/queue/WhatsApp deferred; previous UNIT pause remains binding.

'@
$memoryText = $memoryText.Substring(0, $nextPosition) + $nextAction + $memoryText.Substring($unitPosition)
$tasksText = $tasksText -replace '(?m)^Memory version: 23\r?$', 'Memory version: 24'
$tasksText = $tasksText -replace '(?m)^Updated: 2026-10-11 \(Asia/Jakarta\)\r?$', 'Updated: 2026-10-12 (Asia/Jakarta)'
$taskMarker = 'Memory version: 24'
$taskInsertPosition = $tasksText.IndexOf($taskMarker) + $taskMarker.Length
$taskEntry = @'
- TASK-041 - READY FOR TESTING (simple Telegram demo/source review): three-value setup, uv run main.py + F5, ordinary chat without required FAQ/tenant; optional allowlist and FAQ, default local demo tenant. Clear startup failure/cancellation cleanup and sanitized HTTP logging reviewed. AST22/launch JSON valid,9 reserved worker paths/zero violations, dependencies unchanged; no tests/runtime/provider/Telegram/DB calls. TASK-040 configuration superseded; history remains volatile, production integrations deferred, UNIT pause preserved. Evidence: Ai-services/reports/SIMPLE-TELEGRAM-20261012-orchestrator.md and reports/TASK-041.md; packet tasks/TASK-041.md.

'@
$tasksText = $tasksText.Substring(0, $taskInsertPosition) + "`r`n`r`n" + $taskEntry + $tasksText.Substring($taskInsertPosition)
$indexMarker = '# Context index'
$indexInsertPosition = $indexText.IndexOf($indexMarker) + $indexMarker.Length
if ($indexInsertPosition -lt $indexMarker.Length) { throw 'Context index marker missing.' }
$indexEntry = '- Simple Telegram chatbot (memory24, supersedes TASK-040 demo setup): Ai-services/tasks/TASK-041.md, Ai-services/reports/TASK-041.md, Ai-services/reports/SIMPLE-TELEGRAM-20261012-orchestrator.md and current README.md. Three required values, uv run main.py/F5; general chat, optional FAQ/allowlist, no business backend. Source-reviewed READY FOR TESTING; runtime unverified, production deferred, UNIT paused.'
$indexText = $indexText.Substring(0, $indexInsertPosition) + "`r`n`r`n" + $indexEntry + $indexText.Substring($indexInsertPosition)
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($memoryPath, $memoryText, $utf8NoBom)
[IO.File]::WriteAllText($tasksPath, $tasksText, $utf8NoBom)
[IO.File]::WriteAllText($indexPath, $indexText, $utf8NoBom)
Write-Output 'Reviewed TASK-041 persisted in canonical memory24, tasks and context index.'
