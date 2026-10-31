$ErrorActionPreference = 'Stop'
$projectRoot = 'E:\Java\cashier-saas'
$memoryPath = Join-Path $projectRoot '.ai\MEMORY.md'
$tasksPath = Join-Path $projectRoot '.ai\TASKS.md'
$indexPath = Join-Path $projectRoot '.ai\context\INDEX.md'
$memoryText = [IO.File]::ReadAllText($memoryPath)
$tasksText = [IO.File]::ReadAllText($tasksPath)
$indexText = [IO.File]::ReadAllText($indexPath)
if ($memoryText -notmatch 'Memory version: 22' -or $tasksText -notmatch 'Memory version: 22') {
    throw 'Canonical version changed; reconcile before writing.'
}
$memoryEntry = '- TASK-040 FastAPI + temporary Telegram local adapter SOURCE-REVIEWED / READY FOR TESTING: single-yield lifecycle/cleanup, live and ready health (503 on bot failure), one serial long poller, pinned Telegram origin/token validation, configured single tenant and strict private-user allowlist, existing OpenRouter graph, bounded volatile history/approved FAQ JSON. Review corrections accepted: stop on ambiguous send, respect429 retry_after, sanitized errors, bounded FAQ binary read. All21 Python files parse statically; baseline hashes show only config/state foundation edits, worker events match12 reserved paths. No tests/startup/import runtime/provider/Telegram/DB commands, dependencies unchanged; real credentials/user IDs remain configurable. Local delivery is volatile, not production durable; WhatsApp Business explicitly deferred. Spring customer/auth/order/ticket and production queue remain pending. Independent UNIT pause preserved. Evidence: Ai-services/reports/TELEGRAM-20261011-orchestrator.md and Ai-services/reports/TASK-040.md; packet Ai-services/tasks/TASK-040.md.'
$memoryText = $memoryText.Replace('Memory version: 22', 'Memory version: 23')
$memoryText = $memoryText.Replace('## Evidence-qualified state', "## Evidence-qualified state`r`n`r`n$memoryEntry")
$oldNext = 'AI Service: environment/OpenRouter foundation and contract inventory source-reviewed. Next establish customer authentication/mapping and scoped business capability contract before web/queue/Meta/Spring integration reservations. Model/API key remain user-configurable; approved FAQ collection must come from business source. No automatic services/tests or old TASK-037. Prior Java UNIT pause is independent and remains binding.'
$newNext = 'AI Service: FastAPI + temporary local Telegram adapter READY FOR TESTING by source review. Operator must configure Telegram bot token, single tenant, allowed numeric user IDs, OpenRouter model/key and optional approved FAQ file locally; README contains setup/startup guidance. Do not start services/provider calls or run tests automatically. User-requested testing may verify lifecycle/health, allowlist/tenant isolation, offsets/rate limits/ambiguous-send handling. Production customer ownership, web auth, durable queue and Spring contracts remain pending; WhatsApp Business deferred by user. Prior Java UNIT pause remains binding; do not resume old TASK-037.'
if (-not $memoryText.Contains($oldNext)) { throw 'Next-action paragraph changed; reconcile before writing.' }
$memoryText = $memoryText.Replace($oldNext, $newNext)
$tasksText = $tasksText.Replace('Memory version: 22', 'Memory version: 23')
$taskEntry = '- TASK-040 — READY FOR TESTING (bounded local adapter/source review): FastAPI lifecycle/health and temporary Telegram long polling, server tenant/private allowlist, bounded FAQ/history, OpenRouter replies and conservative delivery errors. AST21 passes; no runtime/tests/provider/Telegram/DB calls. Credentials pending operator setup, production queue/customer/Spring deferred, WhatsApp deferred by user. Packet Ai-services/tasks/TASK-040.md; evidence Ai-services/reports/TELEGRAM-20261011-orchestrator.md and TASK-040.md. UNIT remains paused.'
$tasksText = $tasksText.Replace('Memory version: 23', "Memory version: 23`r`n`r`n$taskEntry")
$indexEntry = '- Temporary local FastAPI/Telegram adapter (memory23): `Ai-services/tasks/TASK-040.md`, `Ai-services/reports/TASK-040.md`, `Ai-services/reports/TELEGRAM-20261011-orchestrator.md`, current `Ai-services/README.md`. Source-reviewed READY FOR TESTING, no runtime acceptance; WhatsApp deferred, UNIT pause preserved.'
$indexText = $indexText.Replace('# Context index', "# Context index`r`n`r`n$indexEntry")
$utf8Encoding = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($memoryPath, $memoryText, $utf8Encoding)
[IO.File]::WriteAllText($tasksPath, $tasksText, $utf8Encoding)
[IO.File]::WriteAllText($indexPath, $indexText, $utf8Encoding)
Write-Output 'Canonical memory/tasks/context updated to version23; UNIT pause preserved.'
