# TASK-117 F1 report

Status: DONE (not compiled; no shell by role). Test status: NOT RUN (orchestrator runs GREEN).

## Changed files (all modified)
- E:\Java\cashier-saas-ui\src\api\billing.api.ts
- E:\Java\cashier-saas-ui\src\pages\subscription\InvoicePanel.tsx
- E:\Java\cashier-saas-ui\src\language\id.ts
- E:\Java\cashier-saas-ui\src\language\en.ts

## Packet items
- `paymentUrl?: string | null`, `providerCode` adds `'MIDTRANS'`: done.
- `usePayInvoiceMutation` (POST `/merchant/subscription/invoices/{id}/pay`, mock branch returns `mockBillingAdapter.get`): done.
- D10 polling: `useInvoiceQuery` `refetchInterval` 5 s for ISSUED MIDTRANS invoices, stops 2 min after hook mount (ref). Also invalidates `merchant-subscription` and invoice list when a MIDTRANS invoice is fetched in a non-ISSUED state.
- InvoicePanel: "Bayar sekarang" link (same tab) when ISSUED and `paymentUrl`; "Buat tautan pembayaran baru" button (calls `/pay`) when ISSUED MIDTRANS without URL; manual-transfer text now hidden for MIDTRANS; mock buttons unchanged.
- Strings added to id.ts and en.ts: `payNow`, `payNowHint`, `newPayLink`.

## Deviations
- `billing.mock.ts` NOT touched: `paymentUrl` is optional, so no type follow-up needed.
- `CheckoutModal.tsx` NOT touched: `onCreated` already opens InvoicePanel (SubscriptionPage), which now shows "Bayar sekarang" for the new invoice. No redirect is forced automatically.
- Return to `/subscription` after finish URL relies on React Query refetch-on-mount/focus defaults plus the panel poll (poll only runs while InvoicePanel is open).

## Risks / open questions
- Poll window starts at panel mount, not at return from Midtrans; if the user reopens the panel after payment, one fresh 2-min window starts. Acceptable.
- Callers outside paths: platform.mock.ts invoice objects lack `paymentUrl` (optional, fine).
- No credentials/outlet checks added; hooks reuse `useBillingEnabled`.
