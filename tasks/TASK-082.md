# TASK-082: Plan enforcement — Free / Pro / Premium + AI quota (PRD v2.2 §8.15)

Status: READY TO DELEGATE (prepared 2026-11-14, not started). Memory version: 81. Branch `fix/flow-controls-a`. Baseline is the commit at the moment wave 1 starts; the orchestrator writes it here first.

Global rules: identical to `tasks/TASK-076.md` "Global rules" 1-7, reports `reports/TASK-082-<id>.md`. Workers never run Maven, unittest/pytest, npm or the app; J3 may run `uv run python -m grpc_tools.protoc` (or `.venv\Scripts\python.exe -m grpc_tools.protoc`) for stub generation. Never edit V1..V10. Java paths are under `src/main/java/org/lite/project/cashiersaas/`, Python paths under `Ai-services/`.

## Test-writing rule (user, 2026-11-14) — applies to T1, T2 and anyone touching tests
- No new test abstraction layers: no base classes, test helpers/builders/factories, fake adapters, wrapper interfaces or "decoupling" seams created for tests. Use the existing fixtures and helpers (`AbstractPostgresIntegrationTest`, existing setup methods, existing fakes in the same file).
- Do not change production code to make it easier to test (no extra interfaces, no new constructor parameters, no visibility changes).
- Before writing a test, search existing unit and integration tests. If the behaviour is already covered, do NOT add another test. Extend an existing test class/method instead of creating a new file whenever the class under test already has a test class.
- One test per behaviour. Variants of one rule go into one `@ParameterizedTest` / `self.subTest` loop.
- No trivial tests (getters, constants, annotations, mocks asserting mocks). Tier 1 from `reports/TEST-AUDIT-2026-11-14.md` must not come back.
- The report lists, per new test, the existing tests searched and why the new one is not a duplicate.

## User decisions (PRD §8.15, `.ai/DECISIONS.md` "Subscription plans and AI quota")
- D1 Plans: `FREETIER` (owner only, 1 outlet), `PRO` (cashiers, staff, refund, audit viewer, full reports), `PREMIUM` (PRO + AI assistant). Data is never deleted on downgrade.
- D2 AI metered per question: 300/month included in Premium, +200 per top-up, 40/day hard cap per tenant, warn at 80% and 100%. Daily summaries do not count.
- D3 At most 4 LLM calls per question. Token usage is logged per tenant.
- D4 One-time 14-day Premium trial starts automatically when a FREETIER tenant completes its 50th paid order.
- D5 Pilot billing: manual transfer and activation by an operator. No payment integration.

## Orchestrator design decisions (reversible)
- E1 **Base and paid rows.** The existing FREETIER subscription row stays ACTIVE forever as the base. A paid plan is an extra ACTIVE row (`PRO`/`PREMIUM`) with `ends_at`.
  - Effective plan = the highest plan among rows that are `isActiveAt(now)`. A running trial counts as PREMIUM.
  - Expiry downgrades automatically, with no scheduler.
- E2 **Downgrade without mutating data.** Cashier access is computed, not written: a CASHIER membership is usable only while the effective plan is PRO or PREMIUM. Re-upgrading restores access automatically. Owners keep access on every plan.
- E3 **New exception.** `PlanUpgradeRequiredException` (in `exception/runtime`) maps to HTTP **402** in `GlobalExceptionHandler`. Its message is Indonesian and names the required plan ("Fitur ini tersedia di paket Pro."). The UI treats 402 as "show the upgrade offer".
- E4 **Pilot activation.** There is no SUPERADMIN login flow in the code today: `/api/v1/platform/**` is secured, but no token carries SUPERADMIN. For the pilot, activation and top-up are done with an operator SQL runbook (`docs/ops/plan-activation.md`, D1 worker). No new endpoint is created.
- E5 **Java owns entitlements and quota.** The AI service asks Java through gRPC before each question and reports token usage after. The quota month and day follow the tenant's outlet time zone (one outlet per tenant).

## Migration V11 (J1) — `src/main/resources/db/migration/V11__plan_entitlements.sql`
- `subscriptions`: add `CHECK (plan_code IN ('FREETIER','PRO','PREMIUM'))`. Index `(tenant_id, status)` if not present.
- `tenants`: add `premium_trial_started_at timestamptz NULL`.
- New table `ai_usage_periods`:
  - Columns:
    - `tenant_id uuid NOT NULL` (FK tenants)
    - `period_month date NOT NULL` (first day of the month, outlet zone)
    - `questions_used int NOT NULL DEFAULT 0`
    - `bonus_questions int NOT NULL DEFAULT 0`
    - `day_date date NOT NULL`
    - `day_questions int NOT NULL DEFAULT 0`
    - `input_tokens bigint NOT NULL DEFAULT 0`
    - `output_tokens bigint NOT NULL DEFAULT 0`
    - `updated_at timestamptz NOT NULL`
  - `PRIMARY KEY (tenant_id, period_month)`.
  - CHECKs: every counter >= 0.

## Wave 1 — J1 entitlement core
Reserved:
- `enums/PlanCode.java` (new: FREETIER, PRO, PREMIUM, ordered by rank)
- `entity/shared/billing/SubscriptionEntity.java`
- the tenant entity file (add `premiumTrialStartedAt` + named method `startPremiumTrial(Instant)`)
- `repository/SubscriptionRepository.java`
- `repository/MerchantAccessRepository.java`
- `service/PlanEntitlementService.java` (new)
- `exception/runtime/PlanUpgradeRequiredException.java` (new)
- `exception/GlobalExceptionHandler.java` (402 handler only)
- `service/MembershipPlanCatalogService.java`, `dto/response/MembershipPlanResponse.java`
- `service/FnbPaymentService.java` (trial hook only)
- V11

Contract:
- **`PlanEntitlementService`:**
  - `PlanCode effectivePlan(UUID tenantId, Instant now)`.
  - `void require(UUID tenantId, PlanCode minimum)`: throws `PlanUpgradeRequiredException` when below `minimum`.
  - `void startTrialIfEligible(UUID tenantId, Instant now)`: FREETIER effective, `premium_trial_started_at` null, and the paid non-cancelled order count of the tenant has reached 50 → set the trial. Idempotent.
  - A trial is active while `now < premium_trial_started_at + 14 days`.
- **`SubscriptionRepository`:** replace the ambiguous `findActiveForTenant` (`setMaxResults(1)` with several active rows) with a query returning all rows active at `now`. Update every caller (`CashierInvitationService`, others found by grep).
- **`MerchantAccessRepository`** (both queries):
  - Drop the `planCode = 'FREETIER'` filter, so any active subscription qualifies.
  - For role CASHIER, additionally require an active PRO/PREMIUM row or an active trial (E2). Express it in the same criteria query (an EXISTS); no second round trip per request.
  - No other access behaviour changes.
- **Plan catalog:** all three plans available, with monthly and yearly prices as `BigDecimal` (59000/590000, 119000/1190000, FREETIER 0). Static numbers go in the existing constants style; no new config.
- **`FnbPaymentService`:** after a successful pay (same transaction, after the order becomes COMPLETED/PAID), call `startTrialIfEligible`. Never on replay.

## Wave 2 (parallel, after J1 is reviewed)

### J2 REST gating
Reserved: `service/CashierInvitationService.java`, the merchant staff service, `service/FnbRefundService.java`, the merchant audit service, `service/FnbSalesReportService.java`, `service/DailySummaryService.java`, `repository/DailySummaryDispatchRepository.java`.
- **PRO required (402 below PRO):**
  - Cashier invitation `issue` (claim keeps its current checks; an invitation issued during PRO but claimed after a downgrade is refused like an unusable invitation).
  - Staff list/deactivate/reactivate/reset.
  - Owner refund.
  - Audit log viewer.
  - Sales report CSV.
- **Free sales report:**
  - `startDate` must be >= outlet-local today − 29 days, else 402.
  - `hourly`, `cashiers` and `shifts` are returned as empty lists. The summary and top products stay.
- **Daily summary:**
  - `onShiftClosed` and the fallback sweep insert/dispatch only for tenants whose effective plan is PREMIUM (trial included).
  - Do this in the repository query (`activeFnbOutlets`) or one entitlement check per outlet; no N+1 per row inside the retry list.

### J3 gRPC quota + usage
Reserved: `src/main/proto/cashier_service.proto` and `Ai-services/proto/cashier_service.proto` (identical), the generated Python stubs under `Ai-services/app/integrations/grpc/generated/`, the gRPC handler class(es) and tenant access policy under `grpc/`, `repository/AiUsageRepository.java` (new), `entity/.../AiUsagePeriodEntity.java` only if the repository needs it (prefer native upsert without an entity).
- **Every existing AI RPC** (catalog, order status, sales summary, restock, anomalies): require effective PREMIUM; below that, return `FAILED_PRECONDITION` with message `PLAN_PREMIUM_REQUIRED`. Tenant and role checks keep their current order, so no new existence evidence appears.
- **New RPCs:**
```proto
rpc ConsumeAiQuestion (ConsumeAiQuestionRequest) returns (ConsumeAiQuestionResponse);
rpc RecordAiUsage (RecordAiUsageRequest) returns (RecordAiUsageResponse);
message ConsumeAiQuestionRequest { string tenant_id = 1; string user_id = 2; }
message ConsumeAiQuestionResponse { bool allowed = 1; string reason = 2; int32 used = 3; int32 limit = 4; bool warn = 5; }
// reason: "" | "PLAN_PREMIUM_REQUIRED" | "MONTHLY_QUOTA_EXHAUSTED" | "DAILY_CAP_REACHED"; limit = 300 + bonus; warn = used >= 80% of limit
message RecordAiUsageRequest { string tenant_id = 1; int64 input_tokens = 2; int64 output_tokens = 3; }
message RecordAiUsageResponse {}
```
- **`ConsumeAiQuestion`:**
  - One atomic SQL upsert on `ai_usage_periods` that resets `day_questions` when `day_date` changes.
  - It increments only when `questions_used < 300 + bonus_questions` and `day_questions < 40`.
  - Concurrency safe without the tenant lock.
- **`RecordAiUsage`:** adds tokens; never fails the caller (errors are logged without content).
- Both RPCs: same service-key and tenant checks as the others; owner role only.

## Wave 3 — P1 Python (after J3)
Reserved: `Ai-services/app/integrations/grpc/client.py`, `Ai-services/app/chat/service.py`, `Ai-services/app/ai/graph/workflow.py` or `nodes.py` (whichever holds the call), `Ai-services/app/chat/daily_summary.py`.
- **Before the graph runs:** call `ConsumeAiQuestion`.
  - Not allowed → reply with a fixed Indonesian message per reason, without calling the LLM: Premium required / kuota bulan ini habis, top-up / batas harian tercapai.
  - `warn` → append one line with `used/limit` to the answer.
- **Cap the graph at 4 LLM calls:** set `recursion_limit` on `ainvoke`. Recursion exhaustion returns a short Indonesian "pertanyaan terlalu rumit, coba dipersempit" reply.
- **Token usage:** sum `usage_metadata` (input/output) of the AIMessages in the final state and call `RecordAiUsage` fire-and-forget (errors swallowed and logged without content).
- **Daily summary:** the insight call also records its tokens. It never consumes a question.
- **gRPC unavailable:** fail closed for `ConsumeAiQuestion` (generic "sedang tidak tersedia" reply). `RecordAiUsage` failures never fail the chat.

## Wave 4 (parallel)
- **T1 Java tests.** Reserved: existing test classes of the touched services/handlers, plus new test classes only where none exists. IT goes in an existing IT class of the area, or one new `integration/identity/PlanEntitlementIntegrationTest.java`. Must cover:
  - effective plan with base + paid + expired + trial
  - cashier access lost on downgrade and restored on upgrade
  - 402 on invitation and refund
  - Free report window
  - quota upsert at the 300/40 boundaries with a concurrent pair
  - trial starting exactly at the 50th paid order and not on replay
- **T2 Python tests.** Reserved: existing `Ai-services/tests/unit/*.py` files of the touched modules. Must cover: the three refusal reasons, the warn line, recursion cap reply, usage recorded, fail-closed on gRPC error.
- **D1 docs.** Reserved:
  - `docs/ops/plan-activation.md` (new): SQL for activate PRO/PREMIUM for N months, extend, top-up +200, check usage; each statement scoped by tenant id inside a transaction.
  - `docs/contracts/` the gRPC contract section.
  - the Postman collection: plans endpoint response and the 402 examples.

## Orchestrator gate
- Review each diff against this packet (reserved paths only; test rule above for T1/T2).
- Then, only on the user's request: `mvnw -B verify` (Docker on) and `Ai-services\.venv\Scripts\python.exe -m unittest discover -s tests/unit -p test_*.py` plus `-s tests/integration`.
- Update canonical memory, then ask before committing.
- UI (upgrade offer on 402, plan page, quota display) is a separate prompt for the UI repo after this task.
