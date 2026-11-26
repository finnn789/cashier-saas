# TASK-094 J1 report

Status: DONE (code written, not compiled)
Baseline: working tree on `feature/leakage-report` (HEAD cee0f5f), uncommitted.

## Changed files
New:
- `src/main/java/org/lite/project/cashiersaas/controller/MerchantSubscriptionController.java`
- `src/main/java/org/lite/project/cashiersaas/service/MerchantSubscriptionService.java`
- `src/main/java/org/lite/project/cashiersaas/dto/response/MerchantSubscriptionResponse.java`

Modified:
- `repository/AiUsageRepository.java`: `readUsage(tenantId, periodMonth, today)` + `Usage` class (questionsUsed, bonusQuestions, dayQuestions; day counter 0 when day_date != today; no row = zeros).
- `service/PlanEntitlementService.java`: public `activeOutletCount(tenantId)` (wraps existing `activeOutletIds`).

## Contract points
- `GET /api/v1/merchant/subscription`, message "Langganan diambil.", `@Transactional(readOnly = true)`.
- Guard `requireOwnerIgnoringAllowance(principal, principal.getOutletId())`; tenant taken from the guard context (principal tenant). No SecurityConfiguration change (`/merchant/**` already OWNER only).
- planCode = effective plan; activeUntil = max endsAt of active paid rows equal to the effective plan, null if none, any open-ended, or FREETIER/trial-only.
- trial {active, startedAt, endsAt, eligible} (NON_NULL omits null dates); outlets {allowed, active, extra = allowed - 1}; ai only for PREMIUM (monthlyLimit = 300 + bonus, resetsAt = start of next month in tenant zone); outletAddOnMonthlyPrice 49000 on PRO/PREMIUM.
- Nested `public static class`, Lombok getter/setter, no record, no ResponseEntity, no wildcards.

## Deviations
- planCode is typed as the `PlanCode` enum (serialized as its name), same values as the contract.
- Fixed-size queries only (effectivePlan, findActiveAt x2, outlet ids, usage row); no N+1.

## Existing tests to update
None expected. PlanEntitlementService and AiUsageRepository only gained methods.

## Open questions
None. No migration needed.

Test status: NOT RUN (policy)
