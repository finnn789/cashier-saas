TASK-040 implementation is complete and submitted for source review.

Delivered:
- FastAPI app factory with liveness/readiness health endpoints.
- Disabled-by-default, single-instance local Telegram long polling.
- Strict configured tenant scope and Telegram user-ID allowlist.
- Fixed Telegram API host, webhook conflict detection, bounded retry/backoff, safe offset handling.
- Volatile bounded conversation history, `/start`, `/help`, `/reset`, and approved local FAQ JSON.
- Updated `.env.example`, README, PLAN, and [TASK-040 report](E:\Java\cashier-saas\Ai-services\reports\TASK-040.md).

No tests, startup, imports, dependency installs, or external/provider/Telegram/database calls were run, per task policy. Key limitations remain documented: local-only, no durable queue/exactly-once delivery, and no WhatsApp/Spring/order integration.