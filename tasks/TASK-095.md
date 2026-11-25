# TASK-095: POS discount, held orders and refund UI (frontend)

Status: PREPARED, NOT DELEGATED (waiting for the user's go-ahead). UI repo is not git: take a baseline copy of `src/` into the session scratchpad before delegating.

## User instruction (2026-11-24)
From the readiness audit (item 6): the api hooks for discount, hold/replace-items and refund exist but no UI uses them, so the POS cannot discount, reopen a held bill, or refund after payment.

## Verified facts (audit, source read)
- UI hooks exist and are unused: `src/api/fnbOrder.api.ts` `useReplaceFnbOrderItemsMutation` (:237), `useGrantDiscountMutation` (:421), `useRefundFnbOrderMutation` (:460); `useCancelFnbOrderMutation` is used only in `PaymentModal`.
- Backend: discount `FnbDiscountController.java:21` + `FnbDiscountService.java:44-46` (NOMINAL and PERCENTAGE, a reason is required); hold bill = replace items `FnbStaffOrderController.java:50` -> `FnbPosOrderService.java:75`; cancel = `FnbPaymentController.java:27` (unpaid orders only); refund = `FnbRefundController.java:21`, OWNER only (`/merchant/**`), returns stock (`FnbRefundService.java:70`). A paid order cannot be cancelled; refund is the only reversal. No PIN/manager approval exists (deferred by the PRD).
- POS (after a previous fix): closing the PaymentModal leaves the order OPEN (held) instead of cancelling; paying requires an open shift; non-cash payments send `labelId`; cart quantity is clamped 1..100 and max 50 lines.
- `src/pages/transactions/TransactionsPage.tsx` + `TransactionDetailModal.tsx` list paid orders (polling) and detail; no action buttons for refund.
- Audit log already records price, discount, payment, refund and shift actions.

## Pre-delegation checklist (orchestrator, read-only; paste results into a "Verified contract" section)
1. Discount: request DTO (type, value, reason limits), allowed order states (unpaid only?), who may call (cashier or owner), recalculation behaviour (is the order total returned?), max discount rules, error messages.
2. Refund: request DTO (full vs partial lines? reason? payment method?), shift/cash-drawer effect, which statuses are refundable, 404/409 messages; confirm refund is only under `/merchant`.
3. Replace items (hold): body, allowed statuses, effect on totals, and whether the response carries the full order for the cart to reload.
4. POS today: is there a list of held (OPEN) orders and a way to reopen one into the cart? Which endpoint lists OPEN orders (`FnbStaffOrderController` list by status?). Read `src/pages/pos/**` and `TransactionDetailModal.tsx` for current state and drift vs `src/types/fnbOrder.ts`.
Paste results here, then confirm decisions D1-D4.

## Design decisions (orchestrator, user may override)
- D1 Discount: in the POS cart/payment step for an unpaid order, "Beri diskon" opens a modal (type NOMINAL/PERCENTAGE, value, required reason) using `useGrantDiscountMutation`; totals refresh from the server response; the reason is shown on the receipt/detail only if the backend returns it. Show the role restriction from the checklist (if discount is owner-only, hide it for CASHIER).
- D2 Held orders: add a "Pesanan tertahan" list (OPEN orders of the outlet) to the POS with reopen (loads lines into the cart, saving uses `useReplaceFnbOrderItemsMutation`) and an explicit cancel for unpaid orders (`useCancelFnbOrderMutation`, reason required). Shift-pill and payment rules from the previous POS fix stay as they are.
- D3 Refund: in `TransactionDetailModal` for paid orders, OWNER only: "Refund" button + confirm modal with reason, using `useRefundFnbOrderMutation`; after success invalidate the transactions and report queries; show server 409/400 messages. Never rendered for CASHIER.
- D4 Money display via `formatCurrency`; no client-side recalculation of totals; all copy in BOTH language files; `cn` from `@/utils/cn`; lucide icons; shared `Modal`; idempotency keys generated once per user intent and reused on retry where the backend accepts a key.
- Out of scope: PIN or manager approval, partial item refunds if the backend lacks them, kitchen/KDS, offline queue, backend changes, receipt reprint redesign.

## Wave 1

### F1 (`fe-worker`)
Reserved (UI repo): `src/pages/pos/**`, `src/pages/transactions/**`, `src/api/fnbOrder.api.ts` (only the discount, replace-items, refund, held-order-list hooks and their query invalidation), `src/types/fnbOrder.ts`, `src/api/mock/fnbOrder.mock.ts` (mock branches for the new actions), `src/language/id.ts`, `en.ts` (`pos` and `transactions` blocks only).
Report: `reports/TASK-095-F1.md`.
Acceptance:
- A cashier can discount an unpaid order, park it, see it in the held list, reopen it, add items, pay or cancel it.
- An owner can refund a paid order from the transaction detail; a cashier never sees the refund action.
- Totals always come from the server; errors show the server message; no literal copy in the new components.
- Existing pos tests under `tests/unit/pos` still compile (keep the exports and signatures of `cart.ts`); `tsc -b` and `oxlint` clean on touched files.
