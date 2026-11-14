# Explicit project decisions

The PRD and explicit user instructions remain authoritative; this file records decisions captured during AI Services work.

## Subscription plans and AI quota — 2026-11-14

- Explicit user decision: Free = 1 outlet, owner only (owner runs the POS, no cashier invitations, no multi-outlet). Pro = staff/cashier invitations and cashier controls plus the full analytics dashboard. Premium = Pro + owner AI assistant. Multi-outlet is a later per-outlet add-on, not a Pro launch feature (the per-tenant lock must change first).
- User accepted the orchestrator recommendation, with prices lowered to cover infrastructure and token cost:
  - Pro Rp59.000/month or Rp590.000/year.
  - Premium Rp119.000/month or Rp1.190.000/year.
  - AI top-up: +200 questions for Rp29.000.
  - Extra outlet: Rp49.000/month (after multi-outlet exists).
- The AI is metered per question, not by credits or tokens.
  - Premium includes 300 questions/month; daily summaries do not count.
  - Users are warned at 80% and 100%.
  - Hard cap: 40 questions per tenant per day.
  - At most 4 LLM calls per question (today the LangGraph default recursion limit applies; must be capped).
  - Per-tenant token usage is logged from the LLM `usage` field.
- Default model: DeepSeek V4.1 Flash via OpenRouter (`AI_MODEL`). Cost budget Rp100/question; worst case about Rp165 under the current prompt (21000 bytes) and output (1024 tokens) limits.
- Other PRD §16 items closed:
  - AI summary time: after the last shift closes, fallback 23:00 outlet time, Premium only.
  - Printing: browser printing with 58 mm and 80 mm layouts.
  - Cashier discount limit stays 0.
- Downgrade keeps all data: cashiers are deactivated and the bot refuses.
- Pilot billing is manual transfer plus SUPERADMIN activation.
- Free gets an automatic one-time 14-day Premium trial after its first 50 transactions.
- Enforcement is not implemented yet (needs a task packet). Details are in `PRD-MVP.md` §8.15.

## F&B ingredient inventory and recipes — 2026-11-10

- Scope change by explicit user instruction: `PRD-MVP.md` §5 listed "Inventori bahan baku dan resep" as post-MVP; it is now in scope (TASK-061). PRD text itself is not edited; this entry supersedes §5 for this item.
- Stock may go negative; the system warns (LOW/OUT/NEGATIVE from `reorder_level`) and never blocks a sale.
- CASHIER may RESTOCK and WASTE; ADJUSTMENT, ingredient CRUD and recipes are OWNERMERCHANT only.
- One unit per ingredient, no unit conversion. Add-on stock is a recipe line on the option; reusable option groups across products is Phase 2 (after TASK-061).
- Product category stays dynamic data (no FOOD/DRINK enum). Stock is deducted only when an order becomes paid (both settle paths); paid orders cannot be cancelled, so no stock reversal exists.
- Workers must reuse existing code and avoid new files/duplicate functions (see tasks/TASK-061.md "Reuse rules").

## AI Services schema — 2026-10-13

- Latest user choice separates Telegram and WhatsApp identities; earlier transient linking answer is superseded.
- User confirms AI database is empty and authorizes replacing the initial schema snapshot. This does not execute/reset any live database.
- User explicitly selects the most minimal schema and defers durable queues. Inbox/outbox/background queue worker are excluded from current scope; core conversation/history remain.
- Current task prepares schema for both platforms and adapts existing Telegram transport. WhatsApp transport/account verification/business access remain subsequent work.

## Reusable option groups (Phase 2) — 2026-11-10

- Option groups are outlet-level (library) and attached to products through `product_option_groups`; `required/min/max` are group-level only (no per-product override).
- Groups are archived, never deleted (order snapshots reference them); archiving a group attached to a non-archived product returns 409.
- Old product-scoped group/option/option-recipe endpoints are replaced, no compatibility routes. Migration V5 backfills and drops `option_groups.product_id`.
- The inventory PR is not merged; Phase 2 is a stacked branch on `feature/fnb-inventory-recipe` (user decision, merge skipped).

## M7 owner AI — 2026-11-14

- Explicit user decisions (TASK-080): daily summary sent when the last open shift of the outlet closes, fallback 23:00 outlet time, once per outlet per day; text = deterministic template + one LLM insight sentence (sent without it on LLM failure); cashier anomaly = day value > 2 × own 30-day daily average and above minimums (cancellations >= 3, discounted orders >= 3, |cash difference| >= 20000).
- Orchestrator design: three new read-only OWNERMERCHANT-only gRPC RPCs (sales summary, restock suggestions, anomalies); Java owns the trigger and dedupe (`daily_summary_dispatches`, V10) and calls the AI service after commit; the AI service reads data back through gRPC (Java stays the source of truth).

## M6 data owner — 2026-11-14

- Explicit user decisions (TASK-079): cashiers may enter a purchase price on RESTOCK, but average cost and movement unit cost are visible to the owner only; per-menu gross profit = line totals − COGS (before order discount), total gross profit = net sales − COGS; stock count is one batch request per count.
- Orchestrator design: moving average cost on `inventory_balances`, `unit_cost` snapshot on every movement, per-item `order_items.cost_amount` (null = unknown cost) because SALE movements are merged per ingredient across menus; inactive ingredients are not deducted. Migration V9.

## PRD v2.1 — record-only cashier — 2026-11-13

- Explicit user decision: the app only RECORDS transactions; QRIS is not a payment feature (no QRIS display, no gateway, no EDC). Payment methods = cash and non-cash; non-cash requires an owner-defined label (e.g. "QRIS", "Transfer BCA") stored as a text snapshot.
- Mock gateway, attempt and resolve flow are to be deleted from code in M5 (DB data kept); supersedes the "flag" decision of Flow controls A for the long term.
- M5 adds petty-cash expenses inside a shift (reduce expected cash), opening cash prefilled from the last counted cash, sequential receipt number + full receipt, optional customer name + order note, order list filters, fast cashier UI.

## PRD v2 — 2026-11-13

- On the user's request `PRD-MVP.md` was rewritten as v2 (cashier cafe + owner AI assistant, single outlet). It is the product source of truth from now on and absorbs the 2026-11-12 pivot and "Flow controls A".
- Milestones: M4 cashier controls (TASK-076, shifts + cash, void/refund), M5 receipts + local payment (sequential receipt number, full receipt, QRIS manual, optional customer name), M6 owner data (unit cost / COGS, stock count, reports per hour/cashier/shift), M7 AI (daily summary, restock, anomalies, read-only), M8 pilot.
- Orchestrator defaults written into the PRD (user may revise): payment requires an open shift of the receiving user; one open shift per user per outlet; refund is full only, owner only, order stays COMPLETED with payment REFUNDED, stock returned via SALE_REVERSAL; receipt number `INV-YYYYMMDD-NNNN` per outlet per day.

## Flow controls A — 2026-11-13

- Explicit user decisions after the 2026-11-13 business-flow audit (group A, TASK-076):
- Mock gateway stays but behind `app.payment.mock-gateway.enabled` (default false; true in demo/test profiles).
- A CASHIER may cancel an OPEN unpaid order only when it has no discount; a discounted order can be cancelled only by the owner (403 for the cashier).
- Owner can list outlet cashiers, deactivate/reactivate them (sessions revoked, AI member status DISABLED/ACTIVE) and reset a cashier password to a one-time temporary password; self-service password change for every user. Reset refused when the cashier account belongs to another tenant.
- gRPC money fields become decimal strings; gRPC calls carry the Java user id and are scoped to that member's active outlets; the AI CPU tool is removed.

## Pivot: cashier-first POS + owner AI assistant — 2026-11-12

- Explicit user decision: the product becomes a plain cashier (POS) for small F&B; the AI assistant for the owner (and later a web chat for cashiers) is the differentiator. The AI feature itself is NOT part of the refactor.
- Removed by deleting code (git history is the way back): customer QR ordering and every `/api/v1/public/**` route, kitchen queue/KDS, dining tables, WebSocket real-time (TASK-032). DB tables/columns stay; applied migrations V1..V5 are never edited.
- Kept: hold bill, receipts, discounts, owner audit viewer, settings, catalog, inventory/recipes, option groups, sales report, onboarding/invitations, Telegram bot and AI service.
- Order status becomes `OPEN` (held/unpaid), `COMPLETED` (paid), `CANCELLED`; V6 relaxes `sales_orders_status_check`, maps old rows (paid -> COMPLETED, unpaid -> OPEN), then tightens.
- Hold bill moves server-side: a held bill is an OPEN/UNPAID POS order (localStorage hold is removed in the UI). A new endpoint replaces the items of an OPEN, UNPAID POS order (`PUT .../fnb/orders/{orderId}/items`), totals recomputed by the server (TASK-075).
- Telegram bot order-status text: OPEN = "Belum dibayar", COMPLETED = "Selesai (sudah dibayar)", CANCELLED = "Dibatalkan".
- Without WebSocket the UI refreshes order lists by polling every 15 s plus refetch on window focus and after its own mutations; the PRD "visible within 5 seconds" target is not guaranteed any more.
- The UI repository `E:\Java\cashier-saas-ui` is not under git yet (user keeps it that way for now); its refactor is done by an external Antigravity/Gemini agent team from `prompts/refactore_feature_and_uui.md`.
- Backend branch: `refactor/pivot-cashier-ai` from main `cf949ff`.
