# TASK-040 — FastAPI + temporary local Telegram polling

## Baseline and reservation acknowledgement

- Canonical memory baseline: **baseline22**, as recorded by the orchestrator in `tasks/TASK-040.md`.
- Reserved writes acknowledged: `app/main.py`, additive `app/config.py`, additive
  `app/ai/state.py`, `app/telegram/`, `.env.example`, `README.md`, `PLAN.md`,
  optional `.gitignore`, and this report only.
- Dirty-work preservation: the parent Cashier repository has extensive unrelated Java,
  SQL, documentation, and canonical-state changes. `Ai-services/` contains the
  uncommitted TASK-038 foundation and its reports/artifacts. None will be reset,
  reformatted broadly, or treated as TASK-040 output.
- Prohibited operations acknowledged: no tests, build, startup, import execution,
  dependency installation, provider/Telegram/database/Redis calls, or nested agents.

## Checkpoint 0 — reviewed inputs

- Read `../AGENTS.md`, `tasks/TASK-040.md`, `PLAN.md`, `reports/TASK-038.md`, and
  current foundation source/configuration.
- TASK-040 local-only design accepted: one serial long-poller, configured trusted
  tenant and explicit Telegram numeric-user allowlist, private non-bot text only,
  volatile bounded history, approved local FAQ JSON, and no Spring/WhatsApp/order or
  ticket integration.
- Implementation has not started at this checkpoint.

## Checkpoint 1 — local adapter implementation

- Added `app/main.py` with `create_app(settings: Settings | None = None) -> FastAPI`, a
  disabled-by-default lifespan, and public `GET /health/live` / `GET /health/ready` only.
  The app factory explicitly supplies `.env`; `load_settings` itself remains discovery-free
  unless a caller supplies a file. Process environment has Pydantic precedence over dotenv.
- Added Telegram settings and enabled-only validation: secret token, trusted tenant, strict
  JSON integer allowlist (booleans rejected before coercion), 1–50-second polling bound,
  1–60-second send bound, 4,000-character chat bound, and optional FAQ path.
- Added the `telegram` request-context channel, local strict FAQ JSON loader, fixed-host
  HTTP client, serial poller, and allowlisted private-message service. No model-visible or
  inbound field chooses tenant, recipient, chat, or user.
- The poller retains a computed reply in memory if delivery raises so it does not invoke the
  model again for a retry in the same process. Its offset advances only after successful
  delivery or an intentional safe rejection; permanent Telegram authorization/polling
  failures stop the poller and make readiness false.
- Updated `.env.example`, `README.md`, and `PLAN.md` with temporary local scope and safe
  setup/limits. No `.gitignore` update was necessary: it already ignores `.env` and secrets.

## Final checkpoint — SOURCE REVIEW submitted

### Changed reserved paths

- `app/main.py`
- `app/config.py`
- `app/ai/state.py`
- `app/telegram/__init__.py`, `client.py`, `faq.py`, `polling.py`, `service.py`
- `.env.example`, `README.md`, `PLAN.md`
- `reports/TASK-040.md`

### Exact integration signatures

- `create_app(settings: Settings | None = None) -> FastAPI`; module `app = create_app()`
  is import-safe with Telegram disabled by default.
- `load_settings(env_file: Path | None = None) -> Settings`; explicit caller-supplied dotenv
  is supported without implicit discovery.
- `Settings.validate_telegram_runtime() -> None` validates only enabled Telegram setup.
- `load_telegram_faq(path_value: str) -> FaqService` accepts only bounded
  `{tenant: [{question, answer}]}` JSON or produces an empty FAQ service for blank config.
- `TelegramService.reply_for_update(update: dict[str, object]) -> tuple[int, str] | None`
  gates private, non-bot, positive, allowlisted sender/chat text and scopes context as
  `tenant + telegram + sender + chat`.
- `TelegramPoller.run() -> None` is a single serial loop; `ensure_no_webhook() -> None`
  detects but never modifies a configured webhook.

### Static review evidence

- Inspected all changed source text and searched the configured Telegram/settings/lifecycle
  paths. The API origin is the literal `https://api.telegram.org`; redirects are disabled;
  the bot token is only used to form the fixed API path; no URL, payload, response, token,
  tenant ID, user ID, model, or raw exception is logged or returned from health endpoints.
- `sendMessage` uses plain `text` only (no `parse_mode`), validates positive chat ID and a
  4,096-character maximum. `getUpdates` handles 429 with bounded retry-after, temporary
  failures with 1–30-second backoff, and 401/403/409 as a stopped/not-ready poller.
- Only ordinary `message` updates with private chat, non-bot sender, allowed positive user
  ID, and nonblank <=4,000-character text are processed. Edits, channels, groups, service
  updates, unauthorized senders, and nontext updates are deliberately acknowledged without
  a reply. `/start`, `/help`, and `/reset` are local bounded actions.
- Conversation history is volatile and capped at 500 conversations, 10 entries each, and
  2,000 characters per stored entry. FAQ content has bounded tenant/entry/question/answer
  validation and defaults to no business facts.

### Limitations and handoff

- **SOURCE REVIEW submitted; not runtime-verified.** Tests were **NOT RUN BY POLICY**.
  No build, startup, import execution, dependency installation, provider, Telegram, database,
  Redis, or network call was made.
- This is a temporary local one-instance long-polling adapter, not durable delivery or
  exactly-once processing. Offset and pending reply/history state are lost on restart; a
  crash after ambiguous Telegram send can duplicate a reply. Telegram retention may expire
  updates. Redis queue/dedup persistence, production observability, web customer auth,
  WhatsApp, Spring/gRPC, orders, and tickets remain deferred.
- Recommended later testing (only when user requests it): disabled app health; enabled
  validation and sanitized readiness failures; allowlist/private/nontext rejection; FAQ
  tenant isolation; command/reset/history eviction; offset-after-send behavior; rate-limit,
  permanent-error, cancellation, webhook-present, and ambiguous-send retry cases.

## Checkpoint 2 — mandatory orchestrator source-review corrections

- Baseline remains **baseline22** and the independent UNIT pause remains untouched. Re-read the
  appended TASK-040 correction packet; all edits remain within the original reservation.
- Reworked `app/main.py` to have exactly one lifespan `yield`. Startup exceptions are recorded as
  sanitized not-ready state before that yield, instead of catching application-body or teardown
  exceptions and yielding again. Startup-created HTTP resources and a possible task are closed or
  cancelled on startup failure; normal teardown cancels/awaits the task and closes the client.
- `GET /health/ready` now raises HTTP 503 with only `Service is not ready.` for enabled failure,
  stopped/degraded poller, missing resources, or a completed poller task. Disabled Telegram remains
  HTTP-only ready. Receive transport failures set degraded until a fully successful receive/process
  iteration; unexpected exceptions stop the poller instead of looping forever.
- Introduced distinct sanitized `TelegramAmbiguousDeliveryError`. Timeout/network/5xx/malformed
  success results during `sendMessage` stop the poller without offset advancement or automatic
  resend. A cached reply is retried only after a valid known HTTP 429; other known 4xx/3xx rejects
  and invalid/oversized rate-limit metadata stop the poller. Valid `retry_after` is slept in full,
  up to a conservative 3,600-second policy, never clamped earlier.
- Client requests now use a literal absolute `https://api.telegram.org` URL per request and pass
  `follow_redirects=False`, independent of injected-client defaults. The token must match the
  strict numeric-prefix/base64url-like Telegram format, so it cannot inject a path/query/fragment.
  Webhook results require a string `url`; successful send results require a positive integer
  `message_id`; all 3xx responses are failures.
- Removed the duplicated `pydantic_settings` import. `CHAT_MAX_MESSAGE_CHARS` now flows into
  `TelegramService`; accepted messages require `sender.is_bot is False` and matching positive
  private sender/chat IDs in addition to the configured allowlist.
- FAQ loading reads no more than 1 MiB before UTF-8 decode/JSON parsing, rejects normalized tenant
  duplicates, and sanitizes Unicode decode failures.
- Rewrote Telegram README instructions: non-overwriting `.env` copy, a hidden-token PowerShell
  `getUpdates` helper that outputs only sender IDs and emits no raw failure body, `/start`/`/help`/
  `/reset`, Telegram’s 24-hour update retention, exact 429 versus ambiguous-send handling, and
  deferred WhatsApp wording. `.env.example` header now reflects the active local Telegram scope.

## Corrected final handoff — SOURCE REVIEW submitted

### Corrected static evidence

- `app/main.py` has one lifecycle yield (line-local source inspection), readiness checks
  `task.done()` and returns FastAPI HTTP 503 without sensitive state. No application exception is
  caught around the yield or teardown.
- `app/telegram/client.py` contains the only literal Telegram origin, a full-match token pattern,
  request-level redirect disabling, 3xx rejection, typed response-envelope checks, and separate
  ambiguous-delivery, permanent, and valid rate-limit outcomes.
- `app/telegram/polling.py` advances offset only after delivery/deliberate reject, preserves a
  cached reply for known 429 only, sleeps exactly the validated server delay, marks transient
  receive state degraded, and stops on ambiguous/permanent/unexpected failures.
- `app/telegram/service.py` enforces the configured chat maximum plus strict non-bot and private
  sender/chat identity conditions. `app/telegram/faq.py` applies the 1 MiB pre-decode bound and
  duplicate normalized-tenant rejection.

Tests remain **NOT RUN BY POLICY**. No test, startup, import execution, dependency installation,
provider/Telegram/database/Redis/network call, canonical write, or out-of-reservation edit occurred.
This remains source-reviewed local scaffolding, not runtime-verified production delivery; volatile
state and restart duplicate risk remain as documented.

## Checkpoint 3 — final focused FAQ/exception correction

- Baseline22 and the independent UNIT pause remain unchanged. Applied only the final two requested
  source corrections inside the original reservation.
- `app/telegram/faq.py` now opens the configured FAQ file as binary and reads at most
  `MAX_FAQ_FILE_BYTES + 1` bytes before rejecting over-limit content, decoding UTF-8, or parsing
  JSON. It no longer allocates an arbitrary-size file through `read_bytes()`.
- `TelegramClient._raise_transport_failure` now raises both sanitized transport exceptions with
  `from None`, preventing a direct caller traceback from retaining chained raw httpx context such
  as a token-bearing request URL.

Final status remains **SOURCE REVIEW submitted, not runtime-verified**. Tests were **NOT RUN BY
POLICY**; no startup, import execution, dependency, provider, Telegram, database, or network action
occurred.

