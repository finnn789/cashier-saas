# TASK-094: Real Subscription page plus owner plan/quota endpoint (backend + frontend)

Status: PREPARED, NOT DELEGATED (waiting for the user's go-ahead). Branch for the backend part: create from `main` or continue `feature/leakage-report` per the user. UI repo is not git: take a baseline copy of `src/` before delegating F1.

## User instruction (2026-11-24)
From the readiness audit (item 5): `/subscription` shows a fabricated plan, expiry, AI quota and placeholder bank/WhatsApp data. Make it real. The UI has no client for the tenant's current plan, so one small backend endpoint is needed first.

## Verified facts (audit, source read)
- UI `src/pages/subscription/SubscriptionPage.tsx` hardcodes: `addonCount=2` and `selectedPlan='pro'` (:10-11), prices 49000/59000/119000 and `uniqueCode=123` (:14-17), current plan "Pro - Rp59.000" and "14 Desember 2026" (:57,60), "29" days left (:66), AI quota bar 44% / "88" (:234,237), BCA account "123 456 7890" (:255), WhatsApp number (:39). Plan/add-on buttons only toggle local state and toast. `src/api/plan.api.ts` only has the public plan list (`GET /public/plans`, used by the landing page `PricingSection`).
- Backend: plans in `PlanCode` (FREETIER, PRO, PREMIUM); `PlanEntitlementService` (allowed outlets = 1 + sum(extra_outlets) over active paid subscriptions, FREETIER = 1); `SubscriptionEntity` has `extra_outlets` (migrations V11, V12); `MembershipPlanCatalogService` (add-on price Rp49.000/outlet/month, `outletAddOnMonthlyPrice` only on PRO/PREMIUM); public endpoint `GET /api/v1/public/plans`. AI quota: gRPC `ConsumeAiQuestion` / `RecordAiUsage` (`cashier_service.proto`), enforced per plan (PRD Appendix A.1: Premium has 300 questions/month, daily cap 40 per tenant, owner notified at 80% and 100%; Premium Telegram assistant). Premium trial: 14 days, automatic after the first 50 transactions on Free, once per tenant.
- Plans are activated manually by an operator (`docs/ops/plan-activation.md`, `extra_outlets` set by SQL in V12). There is no payment/invoice entity and no self-serve purchase.
- `MerchantOutletController` already returns `allowedOutlets`, `activeOutlets`, `extraOutletPrice`.

## Pre-delegation checklist (orchestrator, read-only; paste results into a "Verified contract" section)
1. Read `SubscriptionEntity` + its repository for plan code, status, period start/end, trial fields, `extra_outlets`; read `PlanEntitlementService` public methods and what already computes current plan / trial.
2. Read the AI usage persistence (entities/tables behind `ConsumeAiQuestion` / `RecordAiUsage`): is there a read method that returns used / limit / resets-at (monthly) and the daily cap; exact limits per plan from PRD Appendix A.1 (lines ~524-560).
3. Pick the sibling controller/service/DTO to mimic (naming, `@JsonInclude`, DTO rules in AGENTS.md "DTO and code design": no record DTOs, no ResponseEntity, no wildcard generics).
4. Guard: owner only, tenant level, and it MUST stay reachable when the token outlet is over its allowance (use the `requireOwnerIgnoringAllowance` pattern in `MerchantAccessGuard`, as the consolidated report does). Add the route to `SecurityConfiguration` (`/api/v1/merchant/**` already owner-only; confirm no extra rule needed).
5. Env: check `src/config/env.ts` for an existing contact/WhatsApp setting; if none, plan to add a `VITE_` setting (no number in source).

## Design decisions (orchestrator, user may override)
- D1 Endpoint `GET /api/v1/merchant/subscription` (OWNER, tenant-level). Response (nullable-safe, named classes): `planCode`, `planName`, `status`, `activeUntil` (null for Free), `trial` {`active`, `endsAt`, `eligible`}, `outlets` {`allowed`, `active`, `extra`}, `ai` {`monthlyLimit`, `monthlyUsed`, `dailyCap`, `dailyUsed`, `resetsAt`} (all null/absent on plans without AI), `outletAddOnMonthlyPrice`. Exact fields finalized after checklist item 1-2; no new tables, no migration expected.
- D2 Read-only: no write endpoint, no payment flow. Upgrades and add-on activation stay manual: the page shows prices from `GET /public/plans` and a "Hubungi kami" call to action using a contact URL from `env` (WhatsApp link built from a `VITE_` setting). Remove the bank account, unique code and fake counters.
- D3 UI sections kept: current plan card, outlet allowance (reuses the same numbers as the Outlets page), AI quota bar (hidden on plans without AI), plan comparison/price list from the public plans, add-on info. Sections that implied purchase are removed or turned into the contact CTA.
- D4 All copy in BOTH language files; money via `formatCurrency`; dates via locale helpers; loading/error states.
- Out of scope: payment gateway, invoices, self-serve upgrade, plan downgrade logic, cashier AI quota (known separate issue), the add-on price change.

## Wave 1 (sequential)

### J1 (`java-worker`)
Reserved: new controller/service/DTO classes for `GET /api/v1/merchant/subscription` under `src/main/java/org/lite/project/cashiersaas/`, narrow additions to `PlanEntitlementService` (or the usage repository) only if a read method is missing, `configuration/SecurityConfiguration.java` only if the checklist shows a rule is needed. No migration expected; if one is needed, stop and report. No tests (unless the user asks). Report: `reports/TASK-094-J1.md`.
Acceptance: endpoint returns real values for Free, Pro and Premium; owner only (cashier gets 404 like other `/merchant` routes); works when the token outlet is over allowance; no N+1; code follows the DTO rules.

### F1 (`fe-worker`, after J1 is reviewed)
Reserved (UI repo): `src/pages/subscription/**`, `src/api/plan.api.ts` (add the hook), `src/api/mock/plan.mock.ts` (new or extend), `src/types/plan.ts`, `src/config/env.ts` (contact setting only), `.env.example` if it exists, `src/language/id.ts`, `en.ts` (subscription block only). Report: `reports/TASK-094-F1.md`.
Acceptance: no hardcoded plan, price, date, quota, account or phone value remains in the page; every number comes from the new endpoint or the public plans; Free, Pro and Premium render correctly in mock mode; `tsc -b` and `oxlint` clean on touched files.
