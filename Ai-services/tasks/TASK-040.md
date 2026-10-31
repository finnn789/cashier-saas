# TASK-040 — FastAPI + temporary Telegram local messaging

Orchestrator reviewed brief, 2026-10-11. Canonical memory baseline22. User explicitly requests continuing FastAPI setup and Telegram messaging, defers WhatsApp Business. You are the designated implementation worker, not orchestrator. Existing root rules about delegation do not require you to delegate again. No nested agents.

Read ../AGENTS.md, PLAN.md, reports/TASK-038.md, existing source, and this packet once. Root Java executor template is reference only: this task is standalone Python, not Java MVP. Preserve all dirty user work and independent UNIT pause. Never read/print real .env secrets.

## Exact reserved writes (relative to Ai-services)

- app/main.py (new)
- app/config.py (existing, additive Telegram configuration only)
- app/ai/state.py (existing, add telegram channel only)
- app/telegram/__init__.py (new)
- app/telegram/client.py (new)
- app/telegram/service.py (new)
- app/telegram/polling.py (new)
- app/telegram/faq.py (new, approved FAQ JSON loader)
- .env.example, README.md, PLAN.md (existing, targeted active direction/setup updates)
- .gitignore (new or existing, ignore local secrets only if necessary)
- reports/TASK-040.md (your unique report)

All other files forbidden, including root canonical, pyproject/lock, Java/SQL/tests, task packet, artifacts. No dependencies needed: existing httpx/FastAPI/Pydantic suffice. No tests/build/startup/import runtime/provider/Telegram/DB commands. Read-only installed source allowed. Orchestrator performs syntax-only review later.

## Approved bounded architecture

Local temporary setup uses long polling, one serial poller process, ordinary private text messages, configured single tenant and strict explicit Telegram user-ID allowlist. This is a local FAQ/chat channel adapter, never proof of business customer ownership. Server TELEGRAM_TENANT_ID controls scope; model/message cannot choose tenant, user, chat, recipient. No order/ticket/Spring/authentication/WebSocket/WhatsApp implementation. No Redis queue required for this bounded local polling stage; document that planned production durable Redis integration remains deferred, local offset/history are volatile. This supersedes production queue requirements only for this local scaffold; do not claim production durability/exactly-once. Polling acknowledges Telegram updates via next getUpdates offset only AFTER reply delivery or deliberate rejection, never before processing. Document crash/send ambiguity duplicate risk, Telegram update retention and one-instance requirement.

FastAPI create_app optional injected Settings; module app=create_app() safe without AI keys and with Telegram disabled. Lifespan owns async HTTP client, FAQ/AI creation only when Telegram enabled, one polling task with managed cancellation/cleanup. Public GET /health/live reports liveness and GET /health/ready reflects configured bot/poller failures accurately (disabled mode ready for HTTP only). Health must not reveal tenant/IDs/tokens/model/error bodies. No public arbitrary chat/send endpoints. No auto external operations on import. Polling may call getWebhookInfo on explicit startup and fail sanitized if webhook exists; never automatically set/delete webhook/drop pending updates.

Settings: telegram_enabled false by default; telegram_bot_token SecretStr optional; telegram_tenant_id blank; telegram_allowed_user_ids JSON list positive integers; telegram_poll_timeout_seconds bounded 1..50; telegram_send_timeout_seconds bounded; telegram_faq_file optional blank. Validate enabled-only dependencies plus AI runtime; tenant nonblank <=128, allowlist nonempty/deduplicated positive IDs, no bool IDs, safe bounded numeric settings/chat max<=4000. Load explicit .env in app factory with sanitized SettingsError/ValidationError, retaining foundation load_settings default no dotenv discovery; env variables override dotenv. Do not overwrite .env. WhatsApp remains disabled/not wired.

httpx client pins https://api.telegram.org only, no arbitrary credential destination; POST getUpdates/sendMessage/getWebhookInfo, timeouts, no redirects, retry/backoff bounded including 429 retry_after. Never log raw exception/URL/response/payload/token: token occurs in path, suppress httpx/httpcore INFO request logging as needed. Validate Telegram envelope; send plain text without parse_mode, bounded <=4096 and Unicode handling. Never let model choose recipient. Fail/retry receive transient errors safely; handle 401/403/409 and rate limiting without tight loops. Do not blindly repeat ambiguous send failures. Keep readiness false for failed/stopped poller. Sequential update processing gives conversation ordering locally; avoid replay model on send retry, use existing computed reply. Explicitly document limits.

Only message.chat.type private, nonbot sender, allowlisted sender ID, positive valid IDs and text length accepted. Ignore edits/channel/group/service/nontext updates safely; do not reply to unauthorized users. /start and /help bounded local explanatory text; /reset clears that scoped volatile history. Context tenant+telegram+sender+chat; bounded history <=10 entries each <=2000 chars, bounded total stored conversations with eviction. Existing agent respond interface unchanged. No fabricated business FAQs: optional JSON file loads mapping tenant -> list of question/answer into existing FaqService/FaqEntry with strict bounded validated content, empty default clearly explained. Invalid FAQ file fails sanitized.

README beginner-friendly Indonesian: BotFather /newbot, token via .env locally, how to find own numeric user ID without posting token or mandatory third-party bots, allowlist, tenant value is local trusted configured business scope not customer auth, OpenRouter model/key, optional approved FAQ JSON example without invented business facts, uv run uvicorn app.main:app --host 127.0.0.1 --port 8001 --workers 1, no --reload for polling, health URLs. State no real bot registration/startup/testing performed; credentials user-configurable. Explain file responsibilities and async/lifespan simply. Official references https://core.telegram.org/bots/api#getupdates and https://core.telegram.org/bots/features#botfather and https://fastapi.tiangolo.com/advanced/events/ . Prefer clear names, small methods and useful Indonesian comments explaining why over clever abstractions. Preserve unrelated foundation.

Before coding write report baseline22/reservation acknowledgment. Persist report after meaningful checkpoint and before final handoff. Report changed paths, exact signatures, acceptance implemented, limits, tests NOT RUN BY POLICY. Complete concrete implementation, then submit SOURCE REVIEW; never claim runtime passes.

## Mandatory orchestrator source-review corrections — first submission

Same reservation, baseline22. Fix concrete findings before acceptance:
1. `main.py` lifespan outer try includes yield and teardown; if app body/cleanup raises it catches and yields twice (invalid asynccontextmanager). Isolate startup failure handling BEFORE one single yield; cleanup must be in finally and close resources on every startup failure. Prefer AsyncExitStack if useful. No catching application exceptions and yielding again.
2. Readiness failure must return HTTP503 with sanitized body, not HTTP200 not_ready. Check task.done() as well as poller status. Transient receive failures must mark degraded until a successful response; unexpected failures should stop rather than loop forever with permanent failed flag. Disabled mode remains HTTP-only ready.
3. Generic send timeout/network/5xx/invalid-success envelope currently retries cached send automatically in run; delivery may already have succeeded, violating brief. Introduce explicit sanitized ambiguous-delivery error for sends and STOP poller/not-ready without advancing offset or automatically resending. Only explicit known rejection such as 429 can retry cached reply. Other permanent rejected sends stop. Make documentation distinguish explicit429 retry from ambiguous delivery operator restart duplicate risk.
4. 429 retry_after is currently clamped to60 then30, retrying BEFORE Telegram's requested duration. Respect valid Telegram retry_after with a conservative upper-bound policy: if unreasonably large, stop/degrade; never retry earlier than server says. Sleeps must remain cancellation responsive (async sleep okay worker app, no runtime here). Retry receive transient with real increasing bounded backoff (currently resets on successful getUpdates even when send fails).
5. `_post` must handle HTTP3xx as failure and validate `getWebhookInfo` result dict with url string and send success result/message id, not accept result=None. Validate token format to prevent path/query/fragment injection; send to literal absolute official origin rather than trusting injected client's base_url. Ensure credentials cannot be redirected even with caller-provided client. No raw URL/error logs.
6. Remove duplicate pydantic_settings import. Pass configured CHAT_MAX_MESSAGE_CHARS into service (currently validated but ignored at hardcoded4000). Require sender.is_bot is False (missing/nonbool must not count as nonbot); private chat sender/chat IDs should match. Tenant/allowlist controlled server still applies.
7. FAQ loader must bound file bytes before read/JSON decode (e.g. <=1MiB); reject duplicate normalized tenant keys rather than silently overwrite, sanitize UnicodeError. Current read_text can read arbitrarily large file.
8. README needs actual beginner usable token-safe PowerShell instructions for obtaining own ID: read token via Read-Host -AsSecureString or hidden local env, invoke official getUpdates locally and select only sender IDs, no output URL/raw errors/token. Do not require user to infer an API call. Explain /start /help /reset, exact24h retention, existing .env copy must not overwrite a populated file. Update stale WhatsApp-active intro/.env template header.
Update report acknowledging corrections and accurately distinguish source review from runtime. No tests/startup/import/API/dependency calls remain allowed.

## Final focused review correction

The corrected FAQ implementation uses read_bytes() BEFORE checking len, so arbitrary large allocation still occurs. Change to a bounded binary read: open rb, read(MAX_FAQ_FILE_BYTES + 1), reject if over bound, then decode/parse. Do not read whole file first. In client._raise_transport_failure raise sanitized errors `from None` so direct caller tracebacks cannot render raw httpx token-bearing URL context. These two small corrections only, within original reservation, update report and stop; no unrelated refactor or tests/runtime calls.
