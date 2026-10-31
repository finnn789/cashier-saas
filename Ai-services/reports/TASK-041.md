# TASK-041 — Simple Telegram AI chatbot

## Checkpoint 0 — baseline, reservation, and scope

- Canonical memory baseline acknowledged: **memory23**.
- Reserved writes acknowledged: `app/main.py`, `app/config.py`,
  `app/telegram/service.py`, `app/ai/system_prompt.md`, new root `main.py`, new
  `.env.example`, `README.md`, `.vscode/launch.json`, and this report only.
- Baseline artifact acknowledged: `reports/artifacts/SIMPLE-TELEGRAM-20261012/`.
  The supplied SHA-256 baseline covers the modified Python/Markdown/launch files and
  confirms `pyproject.toml`, `uv.lock`, `app/telegram/polling.py`,
  `app/telegram/client.py`, and `app/ai/agent.py` are out of scope.
- Dirty-source preservation acknowledged: the parent repository contains extensive
  unrelated Java/SQL/documentation/canonical-state work. It will not be changed,
  reset, or included in this task. The real `.env` will neither be read nor changed.
- Prohibited operations acknowledged: no tests, build, startup, import execution,
  dependency changes, provider/Telegram/database calls, Java/SQL/canonical edits, or
  nested executors.

## Reviewed implementation contract

- The service remains one local FastAPI process with the existing single serial
  Telegram poller, official fixed-origin client, webhook-conflict detection, bounded
  volatile history, optional FAQ, and `/start`, `/help`, `/reset`.
- Minimal normal setup requires only `TELEGRAM_BOT_TOKEN`, `OPENROUTER_API_KEY`, and
  `AI_MODEL`. Telegram defaults to enabled when a token exists; an explicit false is
  the only HTTP-only opt-out.
- The local demo accepts any valid non-bot private sender when its optional allowlist
  is empty. It has no business backend access and must not claim business operations.

Implementation had not started at this checkpoint.

## Checkpoint 1 — bounded implementation complete

- Simplified `app/config.py` to the active OpenRouter, Telegram, optional FAQ, and
  bounded runtime fields. Its project-root dotenv path is independent of cwd. Default
  Telegram mode now requires a nonblank `TELEGRAM_BOT_TOKEN`; only explicit
  `TELEGRAM_ENABLED=false` selects HTTP-only mode. Named validation errors do not
  display values or secrets.
- Updated the FastAPI lifespan to fail startup visibly for missing required chatbot
  configuration or Telegram initialization instead of degrading to silent HTTP-only
  mode. The source has one lifecycle yield and cleanup for a partially created task or
  HTTP client. Existing client/poller files were deliberately retained unchanged.
- Made an empty `TELEGRAM_ALLOWED_USER_IDS` an intentional open private-demo list;
  a nonempty list remains a strict filter. `/start`, `/help`, `/reset`, private/non-bot
  checks, bounded history, and the fixed demo FAQ tenant remain in place.
- Reworked the prompt for ordinary Indonesian conversation while preserving the
  approved-FAQ-only rule for business claims, backend-operation prohibition, and
  no internal context/token disclosure.
- Added root `main.py`, minimal `.env.example`, one VS Code F5 configuration, and
  concise Indonesian README instructions. The real `.env` was not read or modified.

## Final checkpoint — static review and handoff

### Delivered integration signatures

- `load_settings(env_file: Path | None = None) -> Settings` resolves its default dotenv
  from the service project root, safely reports only an invalid field name, and retains
  immutable `SecretStr` credential handling.
- `Settings.validate_telegram_runtime() -> None` and
  `Settings.validate_ai_runtime() -> None` enforce startup values/bounds. Default
  Telegram mode requires the requested token/key/model; `TELEGRAM_ENABLED=false` is the
  deliberate HTTP-only exception.
- `create_app(settings: Settings | None = None) -> FastAPI` preserves live/ready health
  endpoints and has one lifespan yield. Partial startup and normal shutdown cancel the
  single poller and close its HTTP client.
- `TelegramService.reply_for_update(update: dict[str, object]) -> tuple[int, str] | None`
  still accepts only valid private non-bot text with matching sender/chat IDs. Its empty
  optional allowlist now allows such senders; a nonempty list filters strictly.
- Root `main.py` starts `app.main:app` using uvicorn on `127.0.0.1:8001`, one worker,
  no reload, after setting the project root cwd. The VS Code configuration uses that
  runner and the workspace `.venv` interpreter.

### Static evidence

- Parsed `app/main.py`, `app/config.py`, `app/telegram/service.py`, and root `main.py`
  with Python AST only; all passed. No application module was imported.
- Parsed `.vscode/launch.json` with PowerShell JSON parsing; it passed. `git diff --check`
  over every reserved path produced no whitespace errors.
- Compared every entry in `reports/artifacts/SIMPLE-TELEGRAM-20261012/baseline.json`.
  Only the intended reserved pre-existing files differ: `app/main.py`, `app/config.py`,
  `app/telegram/service.py`, `app/ai/system_prompt.md`, `README.md`, and
  `.vscode/launch.json`. Out-of-scope `app/telegram/polling.py`,
  `app/telegram/client.py`, `app/ai/agent.py`, `pyproject.toml`, and `uv.lock` retain
  their supplied hashes. New reserved `main.py`, `.env.example`, and this report are
  present.
- Reviewed configuration/README source occurrences: no legacy web/auth/Spring/WhatsApp/
  Redis/database active settings remain; only the documented three required values and
  optional Telegram values appear. No token, key, model value, sender ID, FAQ contents,
  or raw transport error is logged or returned by the implementation.

### Status, limitations, and recommended operator smoke checks

**READY FOR TESTING — source/static review only.** Tests were **NOT RUN BY POLICY**.
No build, startup, import execution, dependency change, provider/Telegram/database call,
or real `.env` read/write occurred.

The local poller/history remains volatile and single-process; it is not durable or
exactly-once. A later user-authorized operator smoke pass should: copy/fill a local `.env`,
run `uv sync --locked` then `uv run main.py`, verify `/health/live` and `/health/ready`,
send `/start`, a normal Indonesian general question, `/reset`, and a business FAQ question;
then confirm empty and populated allowlist behavior, named missing-field startup failure,
HTTP-only opt-out, webhook conflict, and port-in-use handling. No production backend,
payment/order access, or business-data claim is provided by this demo.

## Correction checkpoint — orchestrator review amendment

- Same designated worker, memory23 baseline, and unchanged TASK-041 reservation.
  Read the appended four source-review corrections before editing.
- Added `Settings.normalize_telegram_tenant_id(value: str) -> str`: blank or whitespace
  legacy tenants become `telegram-local-demo`; nonblank tenants are trimmed and values
  longer than 128 characters reject with a named, sanitized validation error.
- Restored WARNING suppression for the `httpx` and `httpcore` loggers before constructing
  Telegram HTTP resources or the OpenRouter agent.
- Added an explicit startup `asyncio.CancelledError` handler that awaits resource cleanup
  and re-raises cancellation. Normal teardown and the single lifecycle yield are retained.
- Formatting and final static verification are pending at this checkpoint.

## Corrected final handoff — source evidence

- All four orchestrator corrections are implemented. This correction pass wrote only
  `app/config.py`, `app/main.py`, and this report. Ruff was scoped explicitly to those
  two Python files plus reserved `app/telegram/service.py` and root `main.py`; the latter
  two were already formatted and stayed unchanged.
- The existing Ruff configuration uses Python 3.12 and 100-column formatting. Final
  `ruff format --check` reports all four files already formatted. A static source line
  scan reports no lines exceeding 100 columns in those files.
- Python AST parsing passed for all four reserved Python files. AST inspection counts
  exactly one lifespan yield. No application imports or runtime execution occurred.
- Source review confirms the tenant field validator maps empty/whitespace strings to
  the demo scope and rejects normalized nonblank strings over 128 characters; explicit
  configured tenants remain supported. Its error contains only the configuration name.
- Both HTTP logger levels are set to WARNING before `httpx.AsyncClient` and
  `build_agent` construction. Startup cancellation awaits `_cleanup_telegram` before
  bare re-raise; ordinary startup errors and normal teardown retain cleanup. No raw
  SDK or transport exceptions are logged by these paths.
- Rechecked protected baseline SHA-256 hashes: `pyproject.toml`, `uv.lock`,
  `app/telegram/polling.py`, `app/telegram/client.py`, and `app/ai/agent.py` all match
  the supplied TASK-041 baseline.

**READY FOR TESTING — runtime unverified.** No tests, application imports, startup,
provider/Telegram/database calls, dependency/canonical edits, nested agents, or real
`.env` access occurred. Recommended additional later checks are blank legacy tenant
normalization, oversized tenant rejection, absence of INFO token-bearing HTTP URLs,
and HTTP-client cleanup when startup is cancelled during webhook inspection.
