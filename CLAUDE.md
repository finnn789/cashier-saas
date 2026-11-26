# Cashier SaaS — Claude Code

`AGENTS.md` is the binding project guide (orchestrator/worker split, DTO and security rules, test policy). Read it first; this file only adds Claude Code specifics.

- Start each session with `.ai/MEMORY.md`, then `.ai/TASKS.md`; product scope is `PRD-MVP.md` (PRD v3, §0 reconciliation is binding).
- Development is test-first (TDD, see `AGENTS.md`): tests RED first, then code GREEN. The orchestrator runs the task's targeted tests; full suites and Postman only when the user asks. Build with `mvnw.cmd`, never npm.
- Workers share one worktree with disjoint reserved paths per task packet; no worktree per writer.
- Never add a `Co-Authored-By` or other AI attribution trailer to commits or PRs. Commit only after explicit user approval.
- Never commit secrets or `.env` files.
