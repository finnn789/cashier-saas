# TASK-041 — Simple Telegram AI chatbot

Date: 2026-10-12. Worker baseline: canonical memory23. Orchestrator-reviewed architecture below. User explicitly requests simpler Telegram request/response, without complex configuration. This updates the temporary demo behavior of TASK-040; Java UNIT stays paused.

## Reserved writes (only)

- app/main.py
- app/config.py
- app/telegram/service.py
- app/ai/system_prompt.md
- main.py (new runner)
- .env.example (currently absent; recreate)
- README.md
- .vscode/launch.json
- reports/TASK-041.md

Existing dirty source must be preserved where unrelated. Baseline copies/hashes: reports/artifacts/SIMPLE-TELEGRAM-20261012/. Do not read/print/write real .env; it belongs to user. No Java, SQL, dependency, uv.lock, pyproject, canonical .ai, tests, archived report changes. No tests/build/startup/import/provider/Telegram/DB calls. No nested executors. Static AST/JSON review allowed, not runtime.

## Reviewed architecture and behavior

Retain existing FastAPI lifespan, TelegramClient, serial TelegramPoller, OpenRouter LangGraph CustomerServiceAgent and optional FAQ. Avoid replacing working transport/rate-limit/ambiguous-send safeguards. Make startup simple and clear, not silently HTTP-only or unready when credentials are wrong.

Three required .env values: TELEGRAM_BOT_TOKEN, OPENROUTER_API_KEY, AI_MODEL (user-selected model; no paid default). Telegram is enabled when token exists unless explicitly TELEGRAM_ENABLED=false. Blank token at chatbot startup must produce clear named configuration error rather than idle service; explicitly false allows legacy HTTP-only behavior. No mandatory TELEGRAM_TENANT_ID or FAQ file: fixed default local-demo tenant for internal isolated FAQ context, optional configured tenant/FAQ supported. TELEGRAM_ALLOWED_USER_IDS optional; [] allows any valid non-bot private sender, nonempty strictly filters. This public-private-chat demo has no business data/access and must be clearly documented. Preserve per-sender history limits and /start /help /reset.

Settings: remove unused future web/auth/Spring/WhatsApp/Redis/DB fields from active config; keep actual fields consumed by current main/agent/telegram only, useful timeout bounds have defaults and do not appear in minimal .env.example. Read .env explicitly from project root robustly, independent of cwd. Keep SecretStr, frozen config, avoid displaying inputs/errors/secrets. Invalid config error should name the invalid field safely (no values). Startup errors should fail visibly with sanitized actionable messages; no logging raw SDK/network errors (could contain secrets). Keep one poller, cancellation/HTTP cleanup even on partial startup failure; only one yield. Bot webhook conflict must remain non-mutating, explicitly tell operator to resolve webhook, not auto delete. Preserve health endpoints/state objects for debugger.

General chat prompt: reply naturally in Indonesian to ordinary messages, without requiring FAQ for general knowledge. Only optional business-specific claims must use approved FAQ; no claiming business backend operations. No token/context disclosure. Keep graph/tool security rules; don't change agent signatures unnecessarily.

New root main.py: simple main guard runs existing FastAPI via uvicorn on 127.0.0.1:8001, workers=1, no reload; uv run main.py works from Ai-services. Set cwd to project root if needed for FAQ paths. .vscode/launch.json: first/only config program ${workspaceFolder}/main.py, .venv interpreter, cwd, integrated console; no Current File. Keep name clear. Do not add packaging or dependencies to enable uv run main.

README rewrite concise Indonesian: 3-value .env, safe initial copy without overwrite, uv sync --locked, uv run main.py, F5, /start then normal chat, optional allowlist and optional FAQ; explain demo private chat open if list empty, no business backend, in-memory history; troubleshooting named config/webhook/port. No huge future production setup in active quickstart. Prior design remains PLAN.md.

## Acceptance and persistence

Record memory23/reserved writes/baseline in reports/TASK-041.md before code changes, persist each meaningful checkpoint and final paths/limitations. Deliver complete source path: startup -> polling -> authorized text -> general AI response -> send. Maintain optional FAQ behavior and health. Source/static verification only, report READY FOR TESTING (runtime unverified). Do not run tests even if tool routing suggests tester. Provide exact signatures and recommended future operator smoke scenarios; no runtime claims.

## Orchestrator source review corrections (same reservation)

Before acceptance, address these in the same worker thread:

1. Existing dotenv may explicitly contain TELEGRAM_TENANT_ID= with no value. Make blank/whitespace tenant normalize to telegram-local-demo through a field validator, rather than fail; tenant is not mandatory for the simple chat. Oversized nonblank values still reject.
2. Restore httpx/httpcore WARNING logger levels before any Telegram/OpenRouter transport. Baseline explicitly suppressed these loggers to prevent INFO request URLs exposing Telegram bot token. Do not emit raw exceptions, response bodies or SDK inputs.
3. Ensure partial startup cancellation (asyncio.CancelledError) also closes allocated HTTP resources before propagating cancellation; Exception does not catch CancelledError. Keep normal cleanup and exactly one lifecycle yield.
4. Format changed Python files to existing 100-column style (only reserved files), without tests/import/startup/provider calls. Static parser verification allowed. Record correction checkpoint and final source evidence in report.
