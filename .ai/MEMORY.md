# Project memory

Memory version: 104
Updated: 2026-11-25 (Asia/Jakarta)

Compact on purpose. Full history through v91 (per-task evidence, old reviews) is in Git history.

## Current state (2026-11-20)

- 2026-11-25 (v104): TASK-109 test dedup + legacy chat path removed, tests green, NOT COMMITTED (details in `.ai/TASKS.md`).
- 2026-11-25 (v103): TASK-105 (mock checkout, V18), TASK-106 (SUPERADMIN panel, V19) done + reviewed + compiled, NOT COMMITTED; TASK-107 Python tests fixed (unit 270 OK).
- 2026-11-25 (v102): TASK-104 debt fix (copy locks both outlets, tests aligned to RBAC; test-compile 0, not run) done; user does QA next.
- 2026-11-25 (v101): TASK-103 RBAC (V17 cashier discount cap) and TASK-101 copy menu: J1+F1 done, reviewed, compiled, NOT COMMITTED. Next: TASK-102 API tests (only when the user asks), then commits per task after approval.

- 2026-11-25: TASK-084..087 committed (`23f3a13`, `cee0f5f`). TASK-091 (FE categories/option groups) done + reviewed, READY FOR TESTING. 2026-11-25: UI tasks TASK-091/092/093/095 closed (DONE, packets+reports deleted). TASK-094 UI ready, backend J1 uncommitted. TASK-097 (stock audit), 098 (summary leakage/margin), 099 (UI attach option groups) done + reviewed; backend/Python NOT COMMITTED. TASK-100 manual purchases (slice 3a, V16) done + reviewed, NOT COMMITTED; slice 3b OCR DEFERRED; AI is analysis only (no action agent). Prepared, not delegated: TASK-103 RBAC (cashier permissions), TASK-101 copy menu, TASK-102 per-outlet price API tests. Backend TASK-096 (`productId` on order items) + TASK-094 J1 (`GET /merchant/subscription`) compiled, NOT COMMITTED in the `feature/leakage-report` tree (HEAD `03c1717` = merge of origin/main).

- Product: `PRD-MVP.md` v3 (§0 reconciliation is binding). Decision: refactor the existing repo, not rebuild. Slice order: (1) opname + leakage [TASK-084], (2) menu HPP/margin [TASK-085], (3) purchase + OCR receipt, (4) AI command agent with approval + per-outlet price, (5) Manager/PIN, stock transfer, RLS, ArchUnit. Offline PWA after pilot. Open user decision: add `MANAGER` role.
- Branch `feature/leakage-report` from `20faa7a` (HEAD = TASK-083 A1, pushed to PR #9 earlier). TASK-084 + TASK-085 + TASK-086 are UNCOMMITTED in the working tree; commit only after user approval, one commit per task.
- TASK-084 (V13 `stock_counts`/`stock_count_lines`, `FnbLeakageService`, REST `.../fnb/reports/leakage`, `/merchant/reports/leakage`, stock count history, gRPC `GetLeakageReport`, Python tool `leakage_report`, Postman folder 21): IMPLEMENTED, REVIEWED, COMPILED. Packet `tasks/TASK-084.md`.
- TASK-085 (V14 `tenants.target_margin_pct` default 60, `FnbMenuMarginService`, `.../fnb/reports/menu-margins`, restock `marginWarnings`, gRPC `GetMenuMargins`, Python tool `menu_margins`, Postman folder 22): IMPLEMENTED, REVIEWED, COMPILED. Accepted: price 0 = OK without margin; warning text always says "turun" (debt). Packet `tasks/TASK-085.md`.
- TASK-086 (tests for 084/085): IN PROGRESS. T3 Python done: `.venv` unittest unit 273 OK. T1 (Java unit: fixes for 7 failing `FnbInventoryCostAndStockCountTest` + new tests) and T2 (Java IT) running. Then orchestrator runs `mvnw -B verify` (needs Docker Desktop) and fixes failures. Packet `tasks/TASK-086.md`.
- TASK-087 (V15 index `audit_logs(tenant_id, entity_id, action)`; additive fields order cashierName/methodLabel/receiptNumber, product categoryName, recipe costEstimate, movement actorName): IMPLEMENTED, REVIEWED, COMPILED. FE report+prompt `reports/TASK-087-FE-CONTRACT.md`. Temporary audit files `.ai/context/contract-data/` (delete when FE aligned).
- Not yet proven: Flyway V13/V14/V15 on a real DB, JPQL on Postgres, Postman run.
- Next Flyway version: V20 (V18 billing checkout TASK-105, V19 platform admin TASK-106; none run on a DB yet).
- Housekeeping done 2026-11-20: all Ruflo/claude-flow tooling removed (user request); `.claude/` is gitignored (project agents are local only); `.ai` compacted (this file, TASKS.md, INDEX.md; older originals are in Git history).

## Last verified baselines
- TASK-083 A1 (`20faa7a`): `mvnw -B verify` green, 601 unit + 192 IT; Python unit 268, integration 20 (14 skipped).
- Unit run 2026-11-20 on TASK-084/085 tree: 726 Java unit run, only 7 errors in `FnbInventoryCostAndStockCountTest` (stale mocks for new code, not production bugs); Python unit 273 OK.

## Working facts (stable)
- Stack: Java 17, Spring Boot 4.1.1, Maven wrapper (`mvnw.cmd`; `clean` fails here because of locked `target/classes/aiohttp`, build without clean), PostgreSQL 17 via Testcontainers (Docker Desktop must be up), Python 3.12 in `Ai-services/.venv` (`.venv/Scripts/python.exe -m unittest discover -s tests/unit`; pytest not installed). Unit profile: `mvnw -Punit-tests test`.
- Roles: `SUPERADMIN`, `OWNERMERCHANT`, `CASHIER`. `/merchant/**` owner only, `/cashier/**` owner + cashier. Plans: FREETIER / PRO / PREMIUM; Premium-only AI over gRPC; reports/leakage/margins need PRO.
- Order status OPEN/COMPLETED/CANCELLED; payments record-only (cash or labelled non-cash); refund owner-only; stock moves via `inventory_movements` (SALE/RESTOCK/WASTE/ADJUSTMENT/SALE_REVERSAL) with moving-average cost (V9).
- Java <-> Python: gRPC (read tools, tenant/outlet scoped by Java) + REST internal (daily summary, member sync). Proto copies must stay identical: `src/main/proto/cashier_service.proto` and `Ai-services/proto/cashier_service.proto`; regenerate Python stubs after a proto change.
- Workflow: orchestrator plans and reviews; workers (`.claude/agents`: java-worker, python-worker, test-worker sonnet; scout, tidy-worker haiku) write code in reserved paths. Tests run only on explicit user request. No AI attribution in commits. Reuse existing code; DTOs are named classes (no records), `ApiResponse` returns.
- Cost note: keep sessions short; read `.ai/MEMORY.md` + `.ai/TASKS.md` only (both compact now).
