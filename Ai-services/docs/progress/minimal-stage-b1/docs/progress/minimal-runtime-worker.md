# Minimal runtime worker — checkpoint 1

Baseline: canonical memory version 32 plus the explicit 13 October 2026 MINIMAL amendment. The older eight-table queue contract is superseded.

Reserved paths: `app/chat/repositories.py`, new `app/chat/service.py`, and this report. This response proposes complete contents for those paths; the worker made no workspace edits and called no tools. No other files or removals are proposed in this checkpoint.

Acceptance progress: Repository operations use the six-table shape, bound tenant/channel/platform scope, platform-specific user identity, cross-session duplicate USER lookup for the same owner/chat, active conversation resolution/reset, scoped message insertion, and bounded role-ordered history. ChatService serializes accepted calls, commits USER before provider I/O, retries only categorized transient AI errors outside transactions, commits ASSISTANT before returning, and keeps command and AI error replies ephemeral. There is no durable queue or delivery state.

Frozen public API for the handler and test authors:

- `register_telegram_channel(connection: AsyncConnection, tenant_id: UUID, external_bot_id: int, username: str | None) -> UUID`
- `Repository(tenant_id: UUID, channel_id: UUID, platform: str)`
- `Repository.upsert_user(connection, external_user_id: str, username: str | None, first_name: str | None) -> UUID`
- `Repository.find_duplicate_incoming(connection, user_id: UUID, external_chat_id: str, external_message_id: str) -> bool`
- `Repository.resolve_conversation(connection, user_id: UUID, external_chat_id: str, reset: bool = False) -> UUID`
- `Repository.insert_message(connection, conversation_id: UUID, user_id: UUID, external_chat_id: str, role: str, content: str, external_message_id: str | None = None) -> int`
- `Repository.completed_history(connection, conversation_id: UUID, user_id: UUID, external_chat_id: str, before_message_id: int, limit: int = 64) -> list[tuple[str, str]]`
- `ChatService(database: Database, repository: Repository, ai: OpenRouterClient)`
- `ChatService.handle(*, external_user_id: str, external_chat_id: str, external_message_id: str, username: str | None, first_name: str | None, kind: str, content: str) -> str | None`. Kinds: `TEXT`, `START`, `NEW`, `SESSION`, `INVALID`, `UNSUPPORTED`. Return `None` only for duplicate stored TEXT; otherwise return text for Sender. The handler must validate private human/allowlist, normalize trusted SDK identifiers, and combine Telegram first and last names if desired before calling.

Evidence and test status: No tools, source reads, database access, startup, provider calls, or tests were performed, as instructed. Proposal requires orchestrator diff review against the separately proposed minimal entities and migration. The repository relies on their exact table and column names.

Remaining work: Checkpoint 2 handler/channel wiring and worker removal; adapt obsolete queue tests after minimal source review; review config/database proposals separately; operator database migration and environment configuration remain outside this worker scope. The in-process lock and global bot ownership serialize this runtime, but replies and delivery are not recoverable after a crash. Commands have no persisted deduplication history; only stored TEXT provider IDs are deduplicated.
