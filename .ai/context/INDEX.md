# Context index

- F&B inventory/recipes/stock warnings (memory54, SOURCE REVIEWED/COMPILES, tests not run): `tasks/TASK-061.md` (binding contract), `reports/TASK-061-W1.md`..`W4.md`, decisions in `.ai/DECISIONS.md` (PRD §5 inventory pulled into scope). Catalog = dynamic categories + option groups per product; stock deducted only when an order becomes paid, in both settle paths (`FnbPaymentService`, `MockFnbGatewayService`); stock may go negative with warnings. UI prompt for Stitch: `prompts/PROMPT_FOR_AGY_FOR_UI_MANAGEMENT_PRODUCT.md`.

- Multichannel minimal schema (memory34, SOURCE REVIEWED/READY FOR TESTING): six mapped tables31 columns and frozen0001_channels snapshot match offline. Direct ChatService with scoped SQLAlchemy queries; platform identities separate; no inbox/outbox/queue worker. Six unit modules adapted,78 static methods not executed. Evidence: Ai-services/docs/channel-schema.md, docs/progress/channels-orchestrator-review.md, minimal-final-evidence.json and minimal-schema-evidence.json. WA transport/durable recovery deferred; no DB/API/.env operations; Java UNIT pause preserved.

- SQLAlchemy chat refactor (memory31, SOURCE REVIEWED/READY FOR TESTING): Ai-services/app/chat/entities.py contains five mapped models; repositories.py owns expression queries; worker.py uses named operations; migrations/env.py uses Base.metadata. Two existing unit modules adapted (78 static methods overall), tests NOT RUN for refactor. Evidence: Ai-services/docs/progress/sqlalchemy-orchestrator-review.md, sqlalchemy-worker-progress.md and sqlalchemy-final-evidence.json. Historical74 unit passes are pre-refactor only.

- Telegram unit tests (memory29,74 pass/0 failures/errors/skips): Ai-services/tests/unit, Ai-services/docs/progress/telegram-unit-review.md, telegram-unit-final-run.txt and telegram-unit-final-evidence.json. One narrow OpenRouter Retry-After parser regression fixed; no real API/DB/migrations or Java UNIT execution. Unit mock boundaries remain explicit.

- Telegram persistent conversation (memory26, source reviewed/READY FOR TESTING): Ai-services/docs/telegram-conversation-design.md, docs/progress/telegram-orchestrator-review.md, telegram-worker-progress.md, telegram-final-evidence.json, and current README. Current minimal polling source supersedes old LangGraph/demo summaries; PostgreSQL migration/runtime/testing pending. Prior UNIT pause unchanged.

- Simple Telegram chatbot (memory24, supersedes TASK-040 demo setup): Ai-services/tasks/TASK-041.md, Ai-services/reports/TASK-041.md, Ai-services/reports/SIMPLE-TELEGRAM-20261012-orchestrator.md and current README.md. Three required values, uv run main.py/F5; general chat, optional FAQ/allowlist, no business backend. Source-reviewed READY FOR TESTING; runtime unverified, production deferred, UNIT paused.

- Temporary local FastAPI/Telegram adapter (memory23): `Ai-services/tasks/TASK-040.md`, `Ai-services/reports/TASK-040.md`, `Ai-services/reports/TELEGRAM-20261011-orchestrator.md`, current `Ai-services/README.md`. Source-reviewed READY FOR TESTING, no runtime acceptance; WhatsApp deferred, UNIT pause preserved.

- AI Service foundation/contract review completed (memory21): `tasks/TASK-038.md`, `tasks/TASK-039.md`, `reports/AI-SERVICES-DELEGATION-20261011.md`, `Ai-services/reports/TASK-038.md` and `TASK-039.md`. OpenRouter environment and bounded graph/FAQ source-reviewed; full channel/Spring acceptance pending material identity contracts. Original setup is historical baseline; UNIT remains paused.

- AI Customer Service setup and next-session delegation: `Ai-services/PLAN.md`, `Ai-services/README.md`, `.env.example` under Ai-services, and `reports/AI-SERVICES-SETUP-20261011.md`. Memory16; setup complete, implementation deferred by user. WebSocket web chat / Meta webhook / LangGraph / Spring gRPC; customer identity mapping requires contract review.

- Infrastructure: `docs/ruflo-setup.md` — Ruflo installation, verified capabilities, activation, and limitations.
- Canonical entry points: `.ai/MEMORY.md`, `.ai/TASKS.md`.
- Architecture: `.ai/ARCHITECTURE.md` — source-reviewed snapshot, boundaries, Pay First state flow, persistence, gaps and Ruflo execution evidence (2026-10-07).
- Existing repository policy: `AGENTS.md`.
- Product scope: `PRD-MVP.md` (not reviewed as part of Ruflo setup).
- MVP status report: `reports/ARCHITECTURE-MVP-STATUS-20261007.md` — reviewed source inventory, architecture, milestones, current migration limits and historical test evidence; memory version 4.
- Active implementation brief: `settings-audit-realtime-20261007.md` — owner settings/audit and scoped after-commit WebSocket; reservations TASK-030/031/032, memory5.

- Final implementation review: `reports/MVP-20261007-orchestrator.md` — TASK-030/031/032 accepted by source review, memory8; worker execution/reserved writes verified, all READY FOR TESTING.
- Unit-test delegation: `tasks/TASK-033.md`, `tasks/TASK-034.md`, `tasks/TASK-035.md` — user-authorized test creation, memory8 baseline; test-only workers and serialized orchestrator execution. Baseline artifacts: `reports/artifacts/UNIT-20261007/`.
- Paused unit-test checkpoint: `reports/UNIT-20261007-orchestrator.md` — user-requested pause, memory13; TASK-033/034 reviewed, TASK-035 final corrected report/review and all execution pending. Saved source copies/hashes: `reports/artifacts/UNIT-20261007/paused-checkpoint/`, `paused-test-sha256.csv`.

Previous architecture/workflow context was absent at setup time. Architecture, MVP source inventory and scoped worker implementation now have reviewed reports; runtime milestone acceptance and deployment readiness remain unverified.
- Pivot refactor (2026-11-12): `pivot-refactor-rules.md` (rules, removal inventory, invariants) and `pivot-refactor-design.md` (target design, waves). Decisions in `.ai/DECISIONS.md` "Pivot". Packets `tasks/TASK-074.md` (Wave A, removal) and `tasks/TASK-075.md` (Wave B, status + hold edit).
