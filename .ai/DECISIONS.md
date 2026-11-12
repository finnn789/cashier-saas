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
