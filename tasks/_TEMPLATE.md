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

## Wave 1 (parallel)

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

## Wave 2 (after Wave 1 review)
...

## Recommended tests (for when the user requests testing)
- ...
