# TASK-094 F1 report

Status: DONE (not compiled, not linted; no shell).

## Changed files (all modified, UI repo E:\Java\cashier-saas-ui)
- src\pages\subscription\SubscriptionPage.tsx (rewritten)
- src\api\plan.api.ts (added `useSubscriptionQuery`)
- src\api\mock\plan.mock.ts (added `getSubscription`)
- src\types\plan.ts (added `MerchantSubscriptionResponse`)
- src\config\env.ts (`contactWhatsapp`, optional, validated `^[1-9]\d{7,14}$`, empty = hidden)
- .env.example (`VITE_CONTACT_WHATSAPP=`)
- src\language\id.ts, en.ts (subscriptionPage block replaced)

## Packet items
- Real page from `GET /merchant/subscription` + `usePlansQuery`: done. Plan name/prices from public plans, outlets, AI (only when `ai` present, monthly + daily + resetsAt), trial active/eligible copy, add-on price from the endpoint.
- Contact CTA via env WhatsApp: done; without the env only the text CTA shows.
- Removed: fake plan/dates/counters, BCA account, unique code, steppers, upgrade modal, toasts, plan feature lists (were unsourced claims).
- Mock covers FREETIER, PRO, PREMIUM and TRIAL. Select with `?mockPlan=FREETIER|PRO|PREMIUM|TRIAL` (default PRO).

## Deviations
- Plan feature bullet lists dropped (static copy, no data source). Free plan shows Rp0.
- Hook query is enabled only when an outlet id exists (or mock); endpoint is tenant-level, outletId is only in the query key.

## Callers outside my paths that may break
- `subscriptionPage` language keys were used only by this page. `parseClientConfig` now returns `contactWhatsapp` (tests/unit/config/env.test.ts uses toMatchObject, fine).

## Open questions
- Backend should serialize Instants as ISO strings (assumed). `activeUntil` with trial-only plan: expiry line hidden.
- Money fields assumed number or numeric string.

## Credentials/outlet
None required beyond JWT; no secrets stored.

Tests: NOT RUN (policy).
