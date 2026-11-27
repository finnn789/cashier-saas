# TASK-115: AI chat throughput (measure first, parallelise only if needed)

Status: PLANNED. E1 ready; F1 BLOCKED until the user approves after reading E1. Branch `feature/leakage-report`. Baseline commit: `545ac38`.

## User instruction (2026-11-27)
Risk from the ai-services review: every Telegram message is processed one at a time for all tenants.

## Facts (orchestrator, verified in source)
- `app/channels/telegram/bot.py:73` polls with `handle_as_tasks=False`: aiogram itself handles one update at a time. This is deliberate: on a fatal handler failure `handlers.py:178-190` suspends intake so the Telegram offset does not move past an update whose persistence is uncertain.
- `ChatService._serial` (`app/chat/service.py:135`) is a global `asyncio.Lock` around `identify`, `verify_contact`, `choose_tenant`, `handle`. Removing it alone changes nothing because of the line above.
- Idempotency exists: `find_duplicate_incoming` (`service.py`, `handle`).
- Only owners of Premium tenants can ask (Java `consumeAiQuestion`), max 40 questions/day/tenant.

## Global rules (every worker)
- Reports: `reports/TASK-115-<id>.md`.

## Wave 1

### E1 — measure (`scout`, read-only)
Read `Ai-services/logs/ai-services.log` (and rotated siblings if present). Do not print message content, phone numbers, tokens or chat ids in the report.
Report:
- count of `update_handled` events and p50/p95/max of `elapsed_ms`;
- p50/p95 of `ai_graph_invoke_finished.elapsed_ms`;
- the busiest minute: how many updates arrived and the longest wait implied (sum of elapsed_ms of updates handled in that minute);
- number of distinct `tenant_id` values seen in log context;
- time range covered by the log.
State plainly if the log is too small to conclude.

## Wave 2 — BLOCKED (needs user approval after E1)

### F1 — per-chat concurrency (`python-worker`, plus `test-worker` RED first)
Only if E1 shows real queueing (e.g. p95 wait > 10 s at the busiest minute).
Sketch (to be refined into reserved paths when unblocked):
- `handle_as_tasks=True`;
- replace `_serial` with a per-`external_user_id` lock (dict of locks, entry removed when idle) plus a global `asyncio.Semaphore(8)` around the graph call;
- replace "suspend whole intake on fatal failure" with per-update failure handling that relies on `find_duplicate_incoming` for redelivery;
- concurrency tests: two users run in parallel; two messages of one user stay ordered.
