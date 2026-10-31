# Minimal Telegram test adapter proposal

Baseline: canonical progress version 33 and the human-approved six-table MINIMAL amendment. Checkpoint 2's correction to `TelegramChannel.graceful_stop_poller` is accepted: normal and supervised failure use the startup observer and public SDK stop path.

Reserved paths: `tests/unit/test_telegram_intake.py`, `tests/unit/test_channel.py`, and this report. This response proposes complete contents for those paths. No production, dependency, other test, or canonical `.ai` file is proposed or removed. No tools, tests, network calls, database access, or live operations were performed by this worker.

The intake tests use `Intake(settings: TelegramSettings, chat: ChatService, sender: Sender)`, `handle(message: Message, event_update: Update)`, and `process(message: Message)`. They cover all-command access checks, SDK ID normalization, command and profile mapping, the 4000-character boundary, duplicate `None` suppression, UTF-16 chunk order, bounded in-memory PENDING attempts, UNKNOWN/FAILED tail suppression, sanitized fatal suspension, and cancellation propagation. Test helpers use concrete aiogram messages and finite task cleanup.

The channel tests use `TelegramChannel(bot: Bot, dispatcher: Dispatcher, database: Database, intake: Intake)` with no Worker fixture. They cover readiness from actual poller/supervisor/intake/DB state, shared stop, cancellation-safe cleanup, public startup observation, native Dispatcher polling-child teardown in normal and supervised failure, early startup failures/cancellation, and successful scoped repository/ChatService binding through mocked registration. The native SDK tests replace only the network polling child. The tests assert no direct runner cancellation in supervised failure.

Evidence: source-only proposal against the pending minimal handler/channel implementation and accepted stop-path correction. Test status: not run by policy. The orchestrator should review exact imports and Sender attributes against the current source before applying. Remaining test adaptation for repository/service and obsolete queue assertions belongs to separate bounded scopes.
