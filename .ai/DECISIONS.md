# Explicit project decisions

The PRD and explicit user instructions remain authoritative; this file records decisions captured during AI Services work.

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
