# TASK-XXX: <title>

Status: PLANNED. Memory version: <n>. Branch `<branch>`. Baseline commit: `<sha>` (dirty: <list or none>).

## User instruction (<date>)
<what the user asked, in their terms>

## Global rules (every worker)
Worker agents already carry the standing rules (`.claude/agents/*.md` + `AGENTS.md`). Only add task-specific extras here:
- Reports: `reports/TASK-XXX-<id>.md`.
- Next migration version: `V<n>__<name>.sql` / Alembic `<rev>`.
- Reuse: <existing classes/helpers to reuse for this task>.

## Orchestrator design decisions (reversible, user may override)
- D1 ...

## Contracts shared between workers
- `<Class.method(signature)>: <return>` — provider <W1>, consumers <W2>.

## Wave 0 — RED (`test-worker`, before any production code)
Reserved:
- `<test path>`
Do: write the test cases listed under "Test cases"; they must fail until Wave 1 ships.

## Wave 1 — GREEN (parallel, after RED is confirmed; never edit the Wave 0 tests)

### W1 — <scope> (`java-worker`)
Reserved:
- `<path>`
Do:
- ...
Acceptance:
- ...

### W2 — <scope> (`python-worker`)
Reserved:
- `<path>`
Do / Acceptance: ...

## Wave 2 — REFACTOR / follow-up (after GREEN review, tests stay green)
...

## Test cases (TDD - written and RED before any production code)
- <behaviour>: <input> -> <expected result / error> (test class::method, existing or new)
- RED evidence (orchestrator): <command> -> fails because <reason>
- GREEN evidence (orchestrator): <command> -> passes
