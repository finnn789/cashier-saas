# Minimal core test checkpoint

Baseline: canonical version 32 and the human-approved MINIMAL amendment of 13 October 2026. The reviewed schema proposal declares six tables, 31 columns, six foreign keys, and three indexes; no live migration or database evidence is claimed. Reserved paths: `tests/unit/test_repository.py`, `tests/unit/test_conversation.py`, and this report only.

This submission proposes complete replacement contents for both unit test files and this report. It makes no filesystem edits. Queue, outbox, delivery recovery, and Worker assertions are removed because durable queues were deferred. Repository tests inspect real SQLAlchemy expressions compiled for PostgreSQL, including scoped ownership, separate platform identity, cross-session duplicate lookup, active reset, guarded message insertion, nullable assistant provider ID, and bounded ordered history. Metadata assertions inspect declarations only.

ChatService tests cover direct command behavior, duplicate suppression, input bounds, ordered prompt construction, transaction boundaries around provider I/O, assistant persistence before return, bounded transient retry, and ephemeral error responses. They use transaction markers and mocked repository/provider calls, with no persistence backend, sender, Worker, network, or live database.

No tools, commands, tests, migrations, startup, provider calls, or environment access were used. Remaining work: orchestrator reviews and applies these proposals, resolves any review findings against the current source, and later coordinates handler/channel test adaptation. Test status remains not run by policy; no passing result or runtime constraint enforcement is asserted.
