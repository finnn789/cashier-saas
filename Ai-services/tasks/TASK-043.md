# TASK-043 — Minimal Telegram channel /start

User explicitly asks for Telegram bot instantiated when current FastAPI runs and a response to /start. Prior architecture was removed by TASK-042; user supplied the current app/application.py FastAPI layout and then requested FastAPI installation. Canonical memory25 is stale for those changes; current source is authoritative, do not restore old architecture. Java UNIT remains paused.

## Reserved worker writes

- app/application.py (wire one lifespan; keep current / and /health HTTP contracts)
- app/telegram.py (new single Telegram channel module)
- app/channels/telegram/bot.py (final module location in the user's existing channel directory; remove temporary app/telegram.py after relocation)
- .env.example (new, exactly TELEGRAM_BOT_TOKEN= and explanatory comment)
- README.md (brief setup/run/F5, /start and troubleshooting)
- reports/TASK-043.md (unique checkpoint report)

No other writes. main.py, pyproject.toml, uv.lock and .vscode are orchestrator/dependency or user-owned and must remain unchanged by worker. Preserve any concurrent source edits. No new architecture folders, AI models, LangGraph, tenant, allowlist, FAQ, queues, DB/Spring/auth or Settings/BaseSettings configuration. No actual .env read/write/printing. No Java, SQL, parent/canonical/history edits. No nested agents/tests/build/startup/application imports/provider/Telegram/network calls. Static AST parsing allowed.

## Architecture brief

Installed dependencies: aiogram3.31.0, python-dotenv1.2.4 (FastAPI/Uvicorn remain; SDK resolution chose Pydantic2.13.5). Baseline hashes taken after installation under reports/artifacts/TELEGRAM-CHANNEL-20261012/baseline.json. Only configuration: TELEGRAM_BOT_TOKEN from os.environ or project-root .env loaded with python-dotenv override=False. Loading can happen within lifespan/channel start; no token resolution/network at module import. Empty/malformed token gives short named actionable error without displaying token.

Use aiogram Bot/Dispatcher and CommandStart handler replying 'Halo! Bot Telegram sudah aktif. Silakan kirim pesan.' (no AI behavior claim). Handle /start with SDK filter, including deep-link command variants. No required user ID; simple channel greeting carries no business data. Keep long polling as an asyncio background task on the existing FastAPI event loop (not run_polling, not a blocking await before yield). handle_signals=False because Uvicorn owns signals, close_bot_session=False because lifespan owns cleanup. One Uvicorn process/no reload (existing main guard already one process).

Validate token/client with get_me and check get_webhook_info before accepting startup; a configured webhook must fail with simple actionable message, do not delete/reconfigure/drop pending updates. Do not print raw network/SDK exceptions, URLs, token, update bodies, or messages. Suppress aiogram noisy/raw-error logs if needed and issue only generic channel startup/stop/error messages from own logger. A missing token must fail clearly rather than running an apparently active bot. Existing /health is HTTP health; do not redesign its payload or add public bot-message endpoint.

Shutdown/startup-failure/cancellation must stop dispatcher polling, await background task termination, and close bot.session. Read installed aiogram dispatcher source to respect its stop_polling/start_polling lifecycle; avoid a hanging stop_polling before it starts or orphan polling tasks on direct cancellation. Keep implementation simple with a small function or focused channel object if ownership needs one; no generic factories/config frameworks. Catch actual background task failure to retrieve exception safely (without secret logs) so no 'Task exception was never retrieved'. Minimal source, focused names and useful comments only.

## Acceptance and checkpoints

Record baseline25/reservations before editing, persist implementation and final source checkpoints in report. Source path: Uvicorn -> FastAPI lifespan -> one bot/dispatcher -> background polling -> CommandStart -> greeting send. Existing HTTP routes and main entrypoint retained. README: copy env template without overwrite, fill token locally, uv run main.py or F5, send /start, one process. Explain bot is a channel with /start only; general/AI replies are not implemented. No model key required. No runtime/testing or external-call claims; deliver READY FOR TESTING with recommended later operator checks.

## Review/location amendment

Source inventory now confirms user-provided empty app/channels/telegram/ directory. Final code belongs at app/channels/telegram/bot.py, imported as app.channels.telegram.bot; retain all other empty user scaffolds unchanged. Native namespace packages work, so no extra __init__.py is required. Temporary app/telegram.py may be deleted after moving its source; both paths are reserved solely for this transition. Ensure cancellation cleanup requests graceful dispatcher.stop_polling when running: direct cancellation of aiogram start_polling after it creates child polling tasks can orphan those children. Only cancel the runner as fallback before it has started or after its graceful teardown; retrieve task failures safely without printing secrets.

Additional initial-source review findings: relocation must resolve project-root dotenv with Path(__file__).resolve().parents[3]/'.env'. Validation get_me/get_webhook_info needs explicit CancelledError cleanup/rethrow because it is not caught by Exception. Shutdown cancellation must protect one graceful dispatcher-stop/runner-await/session-close cleanup task (shield and await it before propagating cancellation), rather than directly cancel a running SDK runner and orphan its child tasks. Suppress aiogram raw exception logs before validation/polling (e.g. set parent aiogram logger level CRITICAL; own generic lifecycle logger remains usable). Translate README quickstart into concise Indonesian. All corrections remain within the same reservations; no app/tests/network runtime.
