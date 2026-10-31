# Channels runtime worker progress

- Baseline: baseline31; frozen multichannel contract dated 13 October 2026.
- Reservation: `app/chat/repositories.py`, `app/chat/worker.py`, `app/channels/telegram/config.py`, `app/channels/telegram/handlers.py`, `app/channels/telegram/bot.py`, `app/core/database.py`, `.env.example`, and this report. This checkpoint authors only the remaining runtime files and updates this report; it does not revise the reviewed repository proposal.
- Checkpoint 1: repository proposal returned and reviewed by the orchestrator. Review reported parse validity, matching declared columns, scoped profile/history/queue operations, and registration that never retargets an account.
- Checkpoint 2: complete proposed Worker, Telegram configuration/intake/startup, database, and environment template contents supplied for review. This response is an authored proposal, not evidence that workspace files were changed or checked.
- Acceptance progress: Telegram intake serializes external identifiers; startup registers the authenticated bot under the configured AI tenant after advisory ownership is acquired; Worker uses conversation columns and BIGINT message IDs, rejects a non-Telegram repository, and converts chat and delivery IDs only at the Telegram adapter boundary. Database expects revision `0001_channels` and retains the existing owner connection, lock, timeouts, and cleanup. `AI_TENANT_ID` is required and parsed as a UUID with sanitized errors.
- Tests: NOT RUN BY POLICY. No build, startup, API, database, filesystem, network, or tool operations were performed.
- Remaining work: orchestrator review, static compilation, application, and integration with Worker A's schema. WhatsApp transport and live integration remain outside this task.
