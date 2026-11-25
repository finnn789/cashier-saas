# TASK-094: Real Subscription page plus owner plan/quota endpoint (backend + frontend)

Status: J1+F1 DONE, REVIEWED, READY FOR TESTING, backend NOT COMMITTED (2026-11-25, memory v96).

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

## Verified contract (orchestrator, 2026-11-25, source read; FINAL, both workers build to it)
Facts:
- `SubscriptionEntity` (`entity/shared/billing`): `planCode`, `status`, `startsAt`, `endsAt` (nullable), `extraOutlets`; `getPlan()`, `isActiveAt(now)`. `SubscriptionRepository.findActiveAt(tenantId, now)`.
- `PlanEntitlementService`: `effectivePlan(tenantId, now)` (trial -> PREMIUM, else max active plan, else FREETIER), `allowedOutletCount(tenantId, now)`, private `activeOutletIds` (count of active outlets: reuse it, e.g. make a public `activeOutletCount(tenantId)`, or an existing outlet repository count; no duplicate query).
- `TenantEntity`: `premiumTrialStartedAt`, `PREMIUM_TRIAL_DURATION` = 14 days, `isPremiumTrialActiveAt(now)`, `getTimeZone()`. Trial starts automatically at 50 paid orders on FREETIER (`startTrialIfEligible`), once.
- `AiUsageRepository`: `MONTHLY_QUESTIONS` = 300, `DAILY_QUESTION_CAP` = 40, table `ai_usage_periods` (tenant_id, period_month, questions_used, bonus_questions, day_date, day_questions). Only write methods exist; **a read method is missing**. Month and day are in the tenant time zone (`today = LocalDate.now(tenantZone)`, `periodMonth = today.withDayOfMonth(1)`), same as `CashierAiUsageGrpcHandler`. AI is Premium only (`hasPremium`).
- `MembershipPlanCatalogService.OUTLET_ADDON_MONTHLY` = 49000; plan prices come from `GET /api/v1/public/plans` (FE already has `usePlansQuery`).
- Guard: `MerchantAccessGuard.requireOwnerIgnoringAllowance(principal, principal.getOutletId())` (as `OutletService.requireOwner`). `/api/v1/merchant/**` is already OWNER only in `SecurityConfiguration`; no new rule.
- UI: `src/config/env.ts` has no contact setting; `.env.example` exists.

Endpoint: `GET /api/v1/merchant/subscription` (OWNER, tenant level, read only, `@Transactional(readOnly = true)`), message "Langganan diambil.". Response `MerchantSubscriptionResponse` (`@JsonInclude(NON_NULL)`):
```
planCode: "FREETIER" | "PRO" | "PREMIUM"     // effective plan (trial counts as PREMIUM)
activeUntil: Instant | null                  // endsAt of the active paid subscription row that gives the plan; null for FREETIER, open-ended rows, or trial-only
trial: { active: boolean, startedAt: Instant|null, endsAt: Instant|null, eligible: boolean }
        // eligible = never started AND effective plan FREETIER
outlets: { allowed: int, active: int, extra: int }   // extra = allowed - 1
ai: { monthlyLimit: int, monthlyUsed: int, dailyCap: int, dailyUsed: int, resetsAt: Instant }   // ONLY when effective plan is PREMIUM, else absent
        // monthlyLimit = 300 + bonus_questions; dailyUsed = day_questions if day_date = today else 0; no row = 0 used; resetsAt = start of next month in the tenant zone
outletAddOnMonthlyPrice: number | absent      // 49000 when plan is PRO or PREMIUM, absent on FREETIER
```
Nested parts are named `public static class` inside the response (pattern of `FnbPosOrderRequest.Item`). No record, no `ResponseEntity`, no wildcard generics.

## Design decisions (orchestrator, user may override)
- D1 Endpoint `GET /api/v1/merchant/subscription` (OWNER, tenant-level). Response (nullable-safe, named classes): `planCode`, `planName`, `status`, `activeUntil` (null for Free), `trial` {`active`, `endsAt`, `eligible`}, `outlets` {`allowed`, `active`, `extra`}, `ai` {`monthlyLimit`, `monthlyUsed`, `dailyCap`, `dailyUsed`, `resetsAt`} (all null/absent on plans without AI), `outletAddOnMonthlyPrice`. Exact fields finalized after checklist item 1-2; no new tables, no migration expected.
- D2 Read-only: no write endpoint, no payment flow. Upgrades and add-on activation stay manual: the page shows prices from `GET /public/plans` and a "Hubungi kami" call to action using a contact URL from `env` (WhatsApp link built from a `VITE_` setting). Remove the bank account, unique code and fake counters.
- D3 UI sections kept: current plan card, outlet allowance (reuses the same numbers as the Outlets page), AI quota bar (hidden on plans without AI), plan comparison/price list from the public plans, add-on info. Sections that implied purchase are removed or turned into the contact CTA.
- D4 All copy in BOTH language files; money via `formatCurrency`; dates via locale helpers; loading/error states.
- Out of scope: payment gateway, invoices, self-serve upgrade, plan downgrade logic, cashier AI quota (known separate issue), the add-on price change.

## Wave 1 (parallel, fixed contract)

### J1 (`java-worker`)
Reserved: new `controller/MerchantSubscriptionController.java`, `service/MerchantSubscriptionService.java`, `dto/response/MerchantSubscriptionResponse.java`, one read method (+ small result class) in `repository/AiUsageRepository.java`, narrow additions to `PlanEntitlementService` (or the usage repository) only if a read method is missing, `configuration/SecurityConfiguration.java` only if the checklist shows a rule is needed. No migration expected; if one is needed, stop and report. No tests (unless the user asks). Report: `reports/TASK-094-J1.md`.
Acceptance: endpoint returns real values for Free, Pro and Premium; owner only (cashier gets 404 like other `/merchant` routes); works when the token outlet is over allowance; no N+1; code follows the DTO rules.

### F1 (`fe-worker`, in parallel with J1; build to the contract above, mock mode covers FREETIER, PRO and PREMIUM incl. an active trial)
Reserved (UI repo): `src/pages/subscription/**`, `src/api/plan.api.ts` (add the hook), `src/api/mock/plan.mock.ts` (new or extend), `src/types/plan.ts`, `src/config/env.ts` (contact setting only), `.env.example` if it exists, `src/language/id.ts`, `en.ts` (subscription block only). Report: `reports/TASK-094-F1.md`.
Acceptance: no hardcoded plan, price, date, quota, account or phone value remains in the page; every number comes from the new endpoint or the public plans; Free, Pro and Premium render correctly in mock mode; `tsc -b` and `oxlint` clean on touched files.

## F1 extra notes
- Contact CTA: add `VITE_CONTACT_WHATSAPP` (digits only, international format, optional) to `env.ts` (validated like the others; empty = hide the WhatsApp button and show only the text CTA) and to `.env.example` with an empty value. Never put a real number in source.
- Plan names and prices from `usePlansQuery` (`/public/plans`); current plan from the new endpoint. Trial: show "trial Premium sampai <date>" when `trial.active`; when `trial.eligible`, explain the automatic trial after 50 paid transactions (copy only, no counter: the endpoint has none).
- AI quota bar only when `ai` is present; show daily usage too. Outlet numbers from `outlets`.
- Dates in the outlet/browser locale via the existing date helpers; money via `formatCurrency`.
- The page is OWNER only already (TASK-093). Do not touch routes or Sidebar.
- Do not run tests (policy). `tsc -b`/`oxlint` allowed.
