# Tasks

Updated: 2026-11-24 (Asia/Jakarta)
Memory version: 92

Compact on purpose. Full per-task evidence through v91 is in Git history. Task lifecycle rules: `AGENTS.md` "Task lifecycle" (packet in `tasks/`, report in `reports/`, deleted when DONE, one line kept below).

## Active
- TASK-087 — J1 DONE, COMPILED, V15 index; T1 IT written (NOT RUN: Docker off); A1 Postman 8/8 PASS from repo collection (CLI 1.93.1; fixed request-level script type `http:afterResponse`→`afterResponse`, Register Account phoneNumber; run from `postman/collections` cwd); NOT COMMITTED: additive response fields for FE contract (order cashierName/methodLabel/receiptNumber, product categoryName, recipe costEstimate, movement actorName). Packet `tasks/TASK-087.md`; FE report+prompt `reports/TASK-087-FE-CONTRACT.md`.
- TASK-086 — IN PROGRESS: tests for TASK-084/085. T1 Java unit (fix 7 failing `FnbInventoryCostAndStockCountTest`, new `FnbLeakageServiceTest`, `FnbMenuMarginServiceTest`, settings/gRPC tests), T2 Java IT (leakage, margin, gRPC), T3 Python DONE (unit 273 OK). Then orchestrator `mvnw -B verify` + Python integration. Packet `tasks/TASK-086.md`.
- TASK-085 — READY FOR TESTING, NOT COMMITTED: PRD v3 slice 2 menu HPP/margin + margin warnings (V14). Packet `tasks/TASK-085.md`, reports `reports/TASK-085-{J1,J2,D1}.md`, `Ai-services/reports/TASK-085-P1.md`.
- TASK-084 — READY FOR TESTING, NOT COMMITTED: PRD v3 slice 1 stock count header + leakage report (V13). Packet `tasks/TASK-084.md`, reports `reports/TASK-084-{J1,J2,A1,D1}.md`, `Ai-services/reports/TASK-084-P1.md`. Debt: count vs manual adjustment told apart by `count:` idempotency-key prefix; history list computes per count.

## Prepared (packet written, NOT delegated; waiting for the user's go-ahead)
Each packet has a "Pre-delegation checklist" of backend contracts the orchestrator must verify first. Order = priority from the 2026-11-23 readiness audit.
- TASK-091 — FE Categories + Option Groups tabs wired to existing hooks (`tasks/TASK-091.md`).
- TASK-092 — FE staff/cashier invite page + business settings page (`tasks/TASK-092.md`).
- TASK-093 — FE role gating, cashier landing `/pos`, Sidebar real user data, i18n cleanup (`tasks/TASK-093.md`).
- TASK-094 — Subscription: backend `GET /merchant/subscription` (J1) then FE real page (F1) (`tasks/TASK-094.md`).
- TASK-095 — FE POS discount, held orders, owner refund in transaction detail (`tasks/TASK-095.md`).

## Next (PRD v3), no packet yet
- Slice 3: purchases + receipt OCR. Open decisions: storage (S3-compatible MinIO/R2 vs local disk), vision model via OpenRouter.
- Slice 4: AI command agent with approval (`action_proposals`) + per-outlet price. Slice 5: Manager role (user decision: defer or add), PIN, stock transfer, RLS, ArchUnit.
- Backend gaps from the audit: audit log lacks stock events (count/adjust/waste/restock), recipe unit conversion, tenant data export/delete, FR-AI-04 summary lacks 4-week comparison and does not persist an insight; AI daily summary has no leakage/margin; thin gRPC integration coverage for leakage/margin/quota; `Ai-services/docs/channel-schema.md` is stale.
- Pending product decision (parked): outlet add-on price (Rp49.000 vs lower or 2 outlets in Pro) and transaction-volume limits.

## Done (one line each; details in archive)
- TASK-090 reports page real (UI repo, not git): 8 tabs (Ringkasan, Per jam, Per kasir, Per shift, Laba kotor/HPP, Konsolidasi, Kebocoran stok, Margin menu), real CSV, 402 upgrade card, `/reports` owner-only; `tsc -b` 0, oxlint clean, vitest 134 pass; not run against a live backend. Debt: presets send browser-local date (Free 29-day edge), invalid custom range hides all tabs, Free empty detail tabs indistinguishable from "locked", no per-day series or refund list in backend.
- TASK-089 outlets page wired to the multi-outlet API (UI repo, not git): real list/create/update/(de)activate, allowance meter from server, deactivate blocked on the active outlet, owner-only nav/route, "Pindah" opens the existing switcher (no preselect); compiled, vitest pass; not run live. Debt: Sidebar hardcoded `KMB-001`/`Pak Mbo` (now TASK-093), role `null` redirects away from `/outlets` (remote login path unchecked).
- TASK-088 POS fix (UI repo, not git): MANUAL_NON_CASH labels, real shift API (`src/api/fnbShift.api.ts`), closing the payment modal holds the order, qty/line limits, option-group load failure surfaced; agent `.claude/agents/fe-worker.md` added; compiled, vitest pass (the old `fnbOrder.test.ts` break did not occur); not run live. Open: `OpenShiftModal` uses `Date.now` during render (oxlint warning).
- TASK-083 (+A1 `20faa7a`) multi-outlet, per-outlet lock, consolidated sales report, outlet add-on, V12 — committed `97eafa2`/`20faa7a`, verified 601 unit + 192 IT.
- TASK-082 plans + AI quota (V11) — `f9ed23d`, pushed to PR #9. Open: cashiers cannot use the AI bot (ConsumeAiQuestion owner-only).
- TASK-081 test cleanup — `de469f8`. TASK-080 M7 owner AI (V10) — committed. TASK-079 M6 unit cost/COGS, stock count, reports (V9) — `08ff7af`.
- TASK-076/077/078 flow controls, cash shifts + refunds (V7), record-only payments + receipts (V8) — `b383d90`.
- TASK-074/075 pivot (remove QR/kitchen/tables/WebSocket, OPEN/COMPLETED/CANCELLED, V6). TASK-064..073 reusable option groups (V5), concurrency tests, rate limiter, 409 on optimistic lock.
- TASK-061..063 inventory/recipes (V4). TASK-059/060 staff sync + shared Telegram bot + JSON logging. TASK-057/058 gRPC hardening. TASK-054..056 portfolio/demo. TASK-045..052 gRPC + LangGraph. Earlier: TASK-030..036.
