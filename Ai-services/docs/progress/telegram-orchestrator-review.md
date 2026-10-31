# Telegram implementation review — 13 October 2026

Canonical baseline: parent memory25. User authorized code-writing worker delegation;
no TASK.md/report-task created. No Java work or prior paused UNIT work resumed.

## Execution evidence

- Ruflo registered `telegram-durable-20261013` for coordination only.
- Direct CLI worker thread `01a13342-46fe-7de1-b9d4-015ba5e48254` could not read/write
  source because child policy denied shell operations; it made no application edits.
- Automatic approval rejected the launch with automatic approval mode. A subsequent
  explicit sandbox launch was approved but child shell remained blocked.
- Safe alternative: read-only tool-free Codex worker received a non-secret source
  bundle and authored exact proposed files in `telegram-worker-proposal.json`.
  Orchestrator reviews before applying those authored contents; no worker command
  execution or sandbox bypass is used.

## Candidate 1 source review

22 authored files plus removal of app/openrouter.py. Contains AI settings/client,
identity/inbox persistence, serial worker/session/history, delivery/outbox, lifecycle,
PostgreSQL advisory ownership, five-table migration, setup docs, and worker checkpoints.

AST parsing of all17 proposed Python files succeeded. Two missing parameter hints
found: worker command connection, migration env connection. No source imports/runtime.
Scope/bot/identity/chat/session predicates and composite migration foreign keys were
inspected. Provider answer/outbox transaction and duplicate inbox uniqueness inspected.

Candidate1 returned for correction: SQLAlchemy URL exception type, DB close/dispose,
driver I/O timeout, potential orphan aiogram children on runner cancellation, cleanup
when worker already cancelled, and type hints/readability.

Tests NOT RUN BY POLICY. No application startup, provider/Telegram/DB calls or migrations.
At the candidate1 checkpoint no application files had changed and lock resolution was pending.

## Accepted implementation and applied diff

Correction worker emitted seven revised files. Reviewed startup observer and shared
shielded native stop task: no application cancellation of an already-started aiogram
runner. Finally-based worker/bot/DB cleanup handles already-cancelled worker outcomes.
URL validation catches ArgumentError/ValueError and uses safe configuration messages.
NullPool ownership connection never reconnects after invalidation. Provider requests
that crash in PROCESSING recover as error without automatic regeneration.

Before applying, all baseline hashes were rechecked: zero concurrent changes found.
Applied exact reviewed worker-authored contents for22 files; removed only the reserved
old app/openrouter.py. Parent Java, main.py, original app/__init__.py, and actual .env
were not edited. README/design status adjustments were authored by orchestrator as docs.

Final bounded worker edits fix a delivery retry busy loop: wait after transaction ends,
without advancing attempts or holding DB lock. Installed driver source showed close()
has no default timeout; final edit wraps close in explicit timeout and invalidates the
connection through the public API on failure. Engine dispose is always attempted.
These exact3 worker replacements were applied only to database.py and worker.py.
Worker progress appended from its returned authorship evidence.

`uv lock` succeeded:34 packages, added SQLAlchemy2.0.54, asyncpg0.32.0,
Alembic1.20.0 and their dependencies. `uv sync --locked` succeeded; aiogram remains
pinned3.31.0 matching reviewed installed dispatcher. Earlier offline resolve failed
because cache metadata was incomplete; network metadata resolution then succeeded.

Final static evidence:18 application/migration Python files parse, no missing function
parameter/return annotations,22 local import targets exist, protected main.py and
app/__init__.py hashes match baseline. Old client module removed and no stale
app.openrouter imports or webhook configuration fields found. Evidence and hashes:
`telegram-final-evidence.json`; original/revised outputs and JSONL remain alongside.
Git status remains unavailable due parent root resolution; no clean-tree claim.

Status: SOURCE REVIEWED / READY FOR TESTING. This means implementation is available,
not that PostgreSQL, Telegram, or provider runtime acceptance has passed.
Set DATABASE_URL and TELEGRAM_SERVICE_SCOPE, then apply Alembic migration before startup.
Real credentials and existing .env were not read or changed. No tests, database commands,
application startup/imports, Telegram calls or model calls executed.

Known limits: one serial worker may delay other users; all bot deployments must use
the same DB for ownership lock; lost owner connection requires restart; PROCESSING
crash and UNKNOWN send do not auto retry; model prompt budget assumes the documented
>=32768 context window; retention is operator-owned. Recommended runtime scenarios
are in README and worker checkpoint note. Prior Cashier UNIT pause is unchanged.

Reviewed progress persisted in the existing parent canonical MEMORY/TASKS/INDEX,
version25 ->26. Previous history and explicit UNIT pause preserved. The initial
approval rejection was resolved with a read-only, tool-free code-authoring worker;
no implementation remains blocked by that launch rejection.

Reference sources checked during review: installed aiogram Dispatcher3.31.0,
installed SQLAlchemy asyncpg dialect2.0.54 and asyncpg connection0.32.0;
[SQLAlchemy engine settings](https://docs.sqlalchemy.org/en/20/core/engines.html),
[NullPool](https://docs.sqlalchemy.org/en/20/core/pooling.html), and
[asyncpg API](https://magicstack.github.io/asyncpg/current/api/index.html).
