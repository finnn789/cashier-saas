# Telegram durable long polling implementation contract

Orchestrator baseline canonical memory version: 25 (parent .ai/MEMORY.md).
User authorized implementation 13 October 2026 and worker delegation/progress notes.
Previous instruction forbids TASK.md/report-task files; this contract and the unique
progress note replace that paperwork. Do not create tasks/TASK-* or reports/TASK-*.

You are the code-writing executor. Implement the full acceptance path in
docs/telegram-conversation-design.md, revised to aiogram LONG POLLING ONLY.
Read current source; canonical history describes older removed source. Preserve
all unrelated changes. Git status currently fails to access root parent; use provided
docs/progress/telegram-baseline.json hashes for comparison. No claim of clean tree.

## Reserved writes

- app/ai/** (new settings, client, prompts/types as needed)
- app/chat/** (identity/session/persistence/worker as needed)
- app/core/database.py and app/core/__init__.py if needed
- app/channels/telegram/** (existing bot plus focused handlers/settings/sender)
- app/config.py and app/application.py
- app/openrouter.py (remove once callers use new location; no compatibility stub)
- pyproject.toml, uv.lock, alembic.ini, migrations/**
- .env.example (template only; retain current variables)
- README.md
- docs/progress/telegram-worker-progress.md (unique progress report)

No other writes. Do not read, print or write real .env, saved credentials, parent
canonical .ai, Java source/SQL, .codex, .agents, unrelated scaffolds or tests.
No application/provider/Telegram/DB calls, no migrations applied, no runtime startup,
no unit/API/integration tests. AST and whitespace/static checks permitted. Dependency
resolution via uv lock/sync permitted, but do not claim runtime acceptance. Never
bypass sandbox/approval. No nested agents. Return findings and progress to orchestrator.

## Architecture and required behavior

Keep Python >=3.12 FastAPI+aiogram. Keep main.py and existing / and /health contracts.
Use SQLAlchemy async + asyncpg + Alembic, PostgreSQL AI database configured only by
DATABASE_URL. Do not silently use SQLite or memory. No secrets/default credentials.
Root config loads .env once (override=False); settings compose Telegram/AI/DB. Move
OpenRouter-specific settings and client into app/ai; unchanged OPENROUTER_API_KEY and
AI_MODEL variable names. Named readable classes/functions, explicit type hints,
early returns, no clever one-liners, no deep closures/lambdas/unneeded factories or
Java-style patterns. async resource ownership explicit; catch actual exception types.

Identity keyed by server-owned service scope+bot.id+Telegram user.id. Username is
optional mutable profile, display name prefers first+last name, never auth key.
All operational DB queries constrained to scope and bot/identity/session where needed;
composite FKs/constraints prevent cross-scope links. One ACTIVE session per identity/chat.
History survives restart; /start greets identified user without resetting, /new closes
old and creates new session; /session shows current reference. Every command/text
requires private, non-bot, optional allowlist authorization. No Cashier business access.

Five persistent tables identity/session/messages/inbox/outbox with BIGINT Telegram IDs,
UUID internal IDs, UTC timestamps, unique update IDs, unique USER per inbox, unique
ASSISTANT per user turn, ordered messages, outbox keyed by inbox/response kind/chunk
(also command/errors without an ASSISTANT message). /new and text ordered in one
serial worker; same user/history isolated from all others. Poller only intake.

Maintain Dispatcher.start_polling(handle_as_tasks=False, handle_signals=False,
close_bot_session=False). Handler must commit identity+inbox before returning; DB
temporary failure waits/retries inside intake without advancing offset. Aiogram catches
ordinary handler exceptions, so merely raising a DB error loses durable guarantees:
inspect INSTALLED aiogram source and implement clear intake failure supervisor/stop
so unknown intake failure cannot advance offset. Get update_id via dispatcher Update
injection or narrow update middleware; no reflection/monkeypatch/private SDK overrides.
Don't call provider during intake. One process/poller/worker, no reload. Single poller
ownership must be enforced for same bot at DB level (e.g. scoped advisory lock held by
dedicated connection) if needed for safe PROCESSING recovery; no multiworker leases.
Reject active webhook, do not remove it/drop updates automatically.

Worker uses short DB transactions, never across provider/send I/O. Save USER once,
construct bounded history of successful completed pairs + current USER once, system
instruction and bounded untrusted profile data; no profile-as-system instructions.
Max input4000/output1024 tokens, timeout45s baseline. Conservative context budget
with documented model window requirement. Retry transient provider failures bounded,
no auto retry 401/402/403 or ambiguous provider timeout unless explicitly documented.
Store provider response ID/model and ASSISTANT+outbox+turn result atomically. Error
messages go outbox but not model history. A persisted AI answer must never be regenerated
just because sending failed. Sequential chunks respect Telegram UTF16 limits.
SENDING recovered as UNKNOWN after crash, no blind resend; stop remaining chunks of
that response on UNKNOWN/fatal failure so user doesn't get a tail without head. Persist
retry_after on429; other explicit temporary errors bounded; no raw token/URL exceptions.
Worker waits for delivery terminal or retry scheduled according to documented serial
ordering. Health ready includes DB/poller/worker/ownership; liveness existing /health
unchanged. Shutdown stop intake, bounded worker grace, await/cancel safely then close
Bot, HTTP and DB resources without orphan children. Cancellation at startup also cleans.

## Checkpoints and delivery

Before edits, write unique progress note listing baseline25, reservations, approach.
After every meaningful checkpoint update changed files, acceptance progress, evidence,
test status NOT RUN BY POLICY, remaining work. Do not hide partial failures. Final
update must include exact files, architecture contracts, dependency lock result,
static evidence, remaining setup/risk, recommended later test scenarios. Return concise
completion summary to orchestrator. Code must be end-to-end implemented, not stubs.
README in Indonesian: set DATABASE_URL and scope, uv sync --locked, uv run alembic
upgrade head as OPERATOR step (do not execute), uv run main.py/F5 one process; Telegram
commands and recovery/24h pending-update/delivery-UNKNOWN limitation.
