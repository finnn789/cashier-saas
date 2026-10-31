# SQLAlchemy chat persistence refactor: phase 1 worker proposal

Baseline: parent memory version 29, based solely on the supplied source bundle. This is a proposed file set for orchestrator baseline comparison and review; it is not a claim that files were written in the workspace.

Proposed paths: `app/chat/entities.py` (new), `app/chat/repositories.py`, `app/chat/worker.py`, `migrations/env.py`, and this report (new). No removals, dependency changes, or revision changes are proposed.

The five DeclarativeBase mappings describe migration `0001_telegram`: column types and nullability, server defaults, identity generation, checks, unique and composite foreign key constraints, and queue, history and partial indexes. Alembic receives `Base.metadata` in both online and offline configuration. The existing migration remains the only schema creation path.

Repository methods own intake, sessions, messages, outbox, recovery, queue reads, completed history, inbox state changes, and delivery state changes. Queries use mapped tables and SQLAlchemy expressions. Scope and bot are bound from the repository instance, with identity and chat ownership conditions on conversation operations. History keeps the scoped USER to ASSISTANT join, completed inbox filter, current inbox exclusion, descending sequence limit of 32, and chronological prompt order. Recovery retains correlated earlier failed or unknown chunk suppression. Existing conflict keys, UUID generation, sequence increment, and row mapping boundary are preserved.

The worker retains its transaction blocks around recovery, queue read, processing preparation, AI error or success persistence, delivery selection, and send result persistence. Provider and sender I/O and waits remain outside transactions. Commands, retry limits and delays, cancellation behavior, status and error strings, and conservative crash recovery are preserved. Database advisory lock code is untouched.

Checks: NOT RUN BY POLICY. No imports, tests, startup, migrations, database operations, shell commands, or filesystem reads were executed. The proposal has not been compared with the current working tree or compiled against installed SQLAlchemy.

Recommended later checks after review and explicit testing request: metadata parity with migration 0001, PostgreSQL compilation of upsert and correlated recovery update, scoped history and queue ordering, intake idempotency, one USER per inbox, crash recovery, retry and delivery transitions, and transaction boundaries around provider and sender calls. Existing repository and conversation tests may need separate phase 2 interface updates.

Remaining risk: source only review cannot establish installed API compatibility, metadata autogenerate parity, or runtime behavior against PostgreSQL. Orchestrator should compare each proposed path against the baseline and inspect the actual diff before application.
