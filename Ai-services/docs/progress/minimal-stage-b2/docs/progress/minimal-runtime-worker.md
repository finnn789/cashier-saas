# Minimal runtime worker — checkpoint 2

Baseline: canonical state version 33 records reviewed progress. The human-approved 13 October 2026 MINIMAL amendment and original worker scope remain authoritative; the earlier eight-table queue contract is superseded.

Checkpoint 1 proposed `app/chat/repositories.py`, `app/chat/service.py`, and this report. Checkpoint 2 proposes complete `app/channels/telegram/handlers.py`, `app/channels/telegram/bot.py`, and `app/application.py` (docstring only); type-only edits to the two checkpoint 1 files; this updated report; and removal of `app/chat/worker.py`. No workspace writes, tools, live calls, or tests were performed by this worker.

The handler checks private human and allowlist restrictions before all command or text processing. It normalizes SDK IDs to strings, bounds input to 4000 characters, calls ChatService, skips sends on duplicate TEXT, splits replies into UTF-16-safe chunks through the existing splitter, and sends chunks serially through Sender. PENDING delivery receives at most three in-memory attempts per chunk; UNKNOWN, FAILED, or exhausted PENDING stops the remaining chunks. There is no delivery record or restart recovery. Any unhandled handler failure signals the supervisor and suspends the SDK handler until poller cancellation, avoiding a silent offset advance.

TelegramChannel constructs the scoped Repository and ChatService after account registration, retains global bot ownership and public startup observation, and supervises polling, intake failure, and DB ping. Readiness checks those live conditions. A fatal intake causes poller cancellation; ordinary shutdown keeps the shared public graceful stop. Bot and DB close after polling stops. Queue Worker wiring, recovery, task, and readiness conditions are removed.

Frozen public interfaces for test and handler authors:

- `register_telegram_channel(connection: AsyncConnection, tenant_id: UUID, external_bot_id: int, username: str | None) -> UUID`
- `Repository(tenant_id: UUID, channel_id: UUID, platform: str)`
- `Repository.upsert_user(connection, external_user_id: str, username: str | None, first_name: str | None) -> UUID`
- `Repository.find_duplicate_incoming(connection, user_id: UUID, external_chat_id: str, external_message_id: str) -> bool`
- `Repository.resolve_conversation(connection, user_id: UUID, external_chat_id: str, reset: bool = False) -> UUID`
- `Repository.insert_message(connection, conversation_id: UUID, user_id: UUID, external_chat_id: str, role: str, content: str, external_message_id: str | None = None) -> int`
- `Repository.completed_history(connection, conversation_id: UUID, user_id: UUID, external_chat_id: str, before_message_id: int, limit: int = 64) -> list[tuple[str, str]]`
- `ChatService(database: Database, repository: Repository, ai: OpenRouterClient)`
- `ChatService.handle(*, external_user_id: str, external_chat_id: str, external_message_id: str, username: str | None, first_name: str | None, kind: str, content: str) -> str | None`. Kinds: `TEXT`, `START`, `NEW`, `SESSION`, `INVALID`, `UNSUPPORTED`; `None` means duplicate stored TEXT.
- `Intake(settings: TelegramSettings, chat: ChatService, sender: Sender)`; aiogram calls `Intake.handle(message: Message, event_update: Update) -> None`. `Intake.process(message: Message) -> None` is the direct processing entry point for focused tests.
- `TelegramChannel(bot: Bot, dispatcher: Dispatcher, database: Database, intake: Intake)`; `start()`, `ready()`, and `stop()` retain their public roles. `start_telegram_channel(settings: Settings, ai: OpenRouterClient) -> TelegramChannel` retains its signature.

Test status: not run by policy. The orchestrator must review these proposals against the minimal entities/migration and Sender contract before applying. Remaining work is adaptation of applicable repository, service, handler, and channel tests after source review; separate review of config/database proposals; and operator migration/environment setup. There is no durable queue, delivery recovery, or persisted command deduplication.
