# TASK-105 F1 report

Status: DONE (code written, not compiled). Tests: NOT RUN (policy).

## Files
New (UI repo `E:\Java\cashier-saas-ui`):
- `src/api/billing.api.ts` (types + hooks: useQuoteQuery, useInvoicesQuery, useInvoiceQuery, useCheckoutMutation, useMockPayMutation, useCancelInvoiceMutation)
- `src/api/mock/billing.mock.ts`
- `src/pages/subscription/CheckoutModal.tsx`
- `src/pages/subscription/InvoicePanel.tsx`

Modified:
- `src/pages/subscription/SubscriptionPage.tsx`
- `src/api/plan.api.ts` (type `SubscriptionSummary`, mock merges `pendingInvoiceId`/`mockPaymentEnabled`)
- `src/api/index.ts` (export line)
- `src/language/id.ts`, `src/language/en.ts` (subscriptionPage section only; subtitle reworded because plans are no longer admin-only)

`src/api/mock/plan.mock.ts`: not changed (not needed).

## Packet items
- Plan cards "Pilih paket" (PRO/PREMIUM) -> CheckoutModal: cycle toggle, extra outlet stepper 0..20 prefilled from `outlets.extra`, live quote (lines, total, dates, change type label), "Buat tagihan": DONE.
- Idempotency-Key: generated at submit (outside mutationFn) with `crypto.randomUUID()`, kept in a ref and reused on retry; regenerated only when the payload changes (a changed payload with the old key would be a guaranteed server 409): DONE.
- Invoice view as modal inside the page (no route, no `?invoice=`): number, amount, status badge, due countdown, mock box ("Simulasi pembayaran (mock)", Bayar berhasil / Gagal), cancel with optional reason, "Menunggu konfirmasi pembayaran oleh admin" + WhatsApp CTA when mock disabled: DONE.
- Pending banner from `pendingInvoiceId`, invoice history list from `GET /invoices`: DONE.
- Premium paid: "Top-up 200 pertanyaan AI (Rp29.000)" -> AI_TOPUP quote/checkout: DONE (button shown for PREMIUM and not trial-only; server enforces).
- After pay/cancel/checkout invalidate `merchant-subscription`, `billing-invoices`, `public-plans`: DONE.
- Server 409/400 messages shown via `getErrorMessage`: DONE.
- Mock layer: quote/checkout (idempotency replay, 409 on other payload, 409 while ISSUED), mock-pay, cancel, lazy expiry, honors `?mockPlan=` for change type: DONE. Mock does not change the plan shown on the summary after PAID (only invoice + pending id).

## Deviations
- Billing types and the two additive summary fields are defined in `billing.api.ts` / `plan.api.ts`, not `src/types/plan.ts`, because that file is outside my reserved paths. Orchestrator may move them to `src/types/` later.
- `src/language` also gets a hardcoded "Rp29.000" in the top-up button copy (packet wording).

## Callers outside my paths
- `src/api/index.ts` now re-exports `billing.api`; no name collisions found by grep.
- `useSubscriptionQuery` return type widened (superset), existing consumers (OutletsPage, Sidebar etc.) unaffected.

## Open questions
- Real-mode queries are gated on an outlet being selected (same pattern as `useSubscriptionQuery`), although these endpoints are tenant-level; confirm that is desired.
- Mock-pay failure toast uses the error style; backend returns the invoice still ISSUED on FAILED, assumed.

Credentials/tokens: none handled. Tenant never sent by the client.
