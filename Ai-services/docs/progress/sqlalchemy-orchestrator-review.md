# SQLAlchemy persistence review — 13 October 2026
Baseline memory29. User requests query expressions and visible table entities. App query source remains unmodified while worker proposal is pending. Git status cannot resolve parent repository in this sandbox; source hashes are the baseline, not a clean-tree claim.

## Scope and acceptance
- Five typed mapped entities accurately mirror migration0001, including scoped composite foreign keys and partial unique indexes.
- All chat data queries use SQLAlchemy expressions and server scope/bot predicates; worker owns orchestration, repository owns persistence.
- Dedicated AsyncConnection, advisory lock, caller-owned transaction boundaries and conservative provider/delivery recovery retained.
- Alembic metadata wired without applying or rewriting migrations.
- Existing two interface-dependent unit modules adapted after production source review; test execution NOT RUN BY POLICY for this refactor. Historical74 passing tests refer to pre-refactor source.

## Coordination and execution
Ruflo memory search/routing and hierarchical swarm registration performed. Ruflo registration is not code execution. Actual read-only Codex CLI worker receives the supplied non-secret source bundle and returns proposed authored contents without tools. Default launch failed during sandbox app-server initialization; parent launch escalation approved, worker sandbox stays read-only. Event/output evidence will be reviewed before application.

## Protected paths
Source/dependency/migration baseline: sqlalchemy-protected-baseline.json. No .env access, Telegram/OpenRouter/database calls, startup or test execution. Existing paused Java UNIT work not resumed. No TASK/report-task paperwork per persisted user decision; reservation contract sqlalchemy-contract.md and this review serve progress tracking.

Worker startup with user plugins stalled before first event and was stopped (only matched task worker PID10472). Retried authenticated CLI without user plugin configuration; sandbox remains read-only and rules remain loaded. Actual thread01a1341d-f61b-76a2-8476-0c7580b20d95 emitted thread.started/turn.started and authoring commentary, no tool activity. Baseline18 production/migration Python files parse successfully. Source is unchanged while authored phase1 output is pending.

Worker supervision correction: initial CLI output buffering hid active reasoning; its turn was later confirmed interrupted and no proposal existed. Resume attempt failed explicitly: selected model at capacity (sqlalchemy-resume-events.jsonl). Same-thread retry uses gpt-6-sol/medium, still tool-free read-only. No code accepted/applied at this checkpoint. Do not infer ongoing execution or success from registration/thread-start alone.

## Reviewed phase1 applied checkpoint
Same-thread gpt-6-sol proposal completed exit0. Exact five reserved contents staged and reviewed against original source; no removal or tool activity. Before apply every protected source/dependency hash matched. Applied entities, repositories, worker, Alembic env and worker progress exactly as authored.

Installed SQLAlchemy model import and offline PostgreSQL DDL compilation succeeded: five tables,72 columns,9 scoped composite FKs,6 indexes. Reviewed every mapped column/check/default/unique/FK/index against unchanged0001 migration. No create_all, database connection, env import or migration execution. Query roots retain scope/bot; scoped history self joins/current exclusion/DONE/descending32+reverse and correlated tail EXISTS inspected. Worker same transaction boundaries, waits/provider/send outside DB transactions and existing error/status semantics verified by source comparison.

Phase1 is partial while two old SQL-string-dependent tests are being adapted. Required type-only correction Table -> FromClause for alias-aware scope helpers delegated with phase2. No tests run; historical74 passes not evidence for this refactor. Canonical reviewed checkpoint needs memory29 ->30; final will persist after phase2 review.

## Final source review
Phase2 full repository proposal was rejected: diff removed the final closing parenthesis, confirmed SyntaxError before application. It was never applied. Same worker returned three exact type/import edits against valid phase1 source; reviewed and applied after each old-text match was unique. Tests/report applied from reviewed phase2 output after original test hashes matched. No other production change.

Two test modules retain the prior16 meaningful cases and add4 repository contract cases. History/recovery SQL assertions moved from worker mocks to captured real SQLAlchemy statements; worker mocks use named Repository spec and transaction markers. Atomic answer/outbox rollback, provider/send after commit, retry wait outside transaction and conservative recovery coverage retained. Both proposed tests statically parse with complete parameter/return hints. Actual tests NOT RUN BY POLICY. Historical74 results are pre-refactor only; current static test count will be recorded separately.

Final verification:27 production/migration/unit Python files parse, no missing function type hints; all21 worker repository calls resolve to current methods. Installed pure entity/repository imports and PostgreSQL offline DDL compilation succeeded. Protected hash audit shows exactly six reviewed existing paths changed (repository, worker, Alembic env, two unit modules, README), plus the new entity source; all other source/dependency/immutable migration hashes match. Unit inventory78 is STATIC, not a pass count. No test/startup/migration/DB/API/.env operations executed. Canonical MEMORY/TASKS/INDEX updated30 ->31 after review and concurrent-version check. Java UNIT pause preserved.

Status: SOURCE REVIEWED / READY FOR TESTING. Recommended explicit next tests: duplicate updates/profile refresh; scoped session reset/history/current exclusion; sequence mismatch; atomic answer/outbox rollback; provider/send after commit; retry due outside transaction; conservative PROCESSING and SENDING recovery and tail suppression. Real PostgreSQL constraint/autogenerate equivalence and transport/runtime integration remain unverified.
