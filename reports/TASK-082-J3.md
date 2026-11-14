# TASK-082 J3 report: gRPC plan gate, AI quota, usage

Not compiled or run (policy). Python stubs regenerated and import-checked (`ConsumeAiQuestionResponse` constructs).

## Changed files
- `src/main/proto/cashier_service.proto` and `Ai-services/proto/cashier_service.proto` (byte-identical, `cmp` verified): 2 rpcs + 4 messages, text exactly as in the packet.
- `Ai-services/app/integrations/grpc/generated/cashier_service_pb2.py`, `cashier_service_pb2_grpc.py`: regenerated with `-Iproto --python_out=... --grpc_python_out=... proto/cashier_service.proto` (command from Ai-services/README.md, grpcio-tools 1.71.2). The generator emits `import cashier_service_pb2 as ...`; I restored the existing `try: from . import ... except ImportError: import ...` block, so the diff is only the new rpcs.
- `grpc/policy/GrpcTenantAccessPolicy.java`: `hasPremium(tenantId)`, `requirePremium(tenantId)` (FAILED_PRECONDITION "PLAN_PREMIUM_REQUIRED"), constant `PLAN_PREMIUM_REQUIRED`; new constructor parameter `PlanEntitlementService` (before `properties`).
- `grpc/service/CashierCatalogGrpcHandler.java`, `CashierOrderGrpcHandler.java`, `CashierInsightGrpcHandler.java`: one line each, `requirePremium` right after `requireMemberOutlets` / `requireOwnerOutlets` (before argument validation and queries, so no new existence evidence).
- `grpc/service/CashierAiUsageGrpcHandler.java` (new) + `CashierGrpcService.java` (two delegating overrides).
- `repository/AiUsageRepository.java` (new, native SQL, no entity).

## Contract points
- Plan gate in one place (policy); five existing RPCs call it. Global service-key + rate-limit interceptor already covers the new RPCs (it is a global interceptor, no method allowlist).
- ConsumeAiQuestion: allowlist tenant -> `requireOwnerOutlets` -> `hasPremium`; below PREMIUM: `allowed=false, reason=PLAN_PREMIUM_REQUIRED, used=0, limit=0`, no counter change. Otherwise one upsert. `limit = 300 + bonus`, `used = questions_used` (after the call, or current value when refused), `warn = used*5 >= limit*4`. Reason when refused: `MONTHLY_QUOTA_EXHAUSTED` if `used >= limit`, else `DAILY_CAP_REACHED`.
- Constants `MONTHLY_QUESTIONS = 300`, `DAILY_QUESTION_CAP = 40` are `public static final` in `AiUsageRepository`.
- Transactions: `@Transactional` on the two repository methods, handler is not transactional (so the swallowed RecordAiUsage error cannot turn into an UnexpectedRollbackException at the proxy). Everything is a single statement, row-level ON CONFLICT, no tenant lock.
- RecordAiUsage: any exception is caught and logged with a fixed message (exception class only), always returns the empty response. Negative token values are clamped to 0 (the V11 CHECK would otherwise reject them).

## Exact SQL
Consume (one statement, params `tenantId, periodMonth, today, monthly=300, dailyCap=40`):
```sql
INSERT INTO ai_usage_periods (tenant_id, period_month, questions_used, bonus_questions, day_date, day_questions, input_tokens, output_tokens, updated_at)
VALUES (:tenantId, :periodMonth, 1, 0, :today, 1, 0, 0, now())
ON CONFLICT (tenant_id, period_month) DO UPDATE SET
  questions_used = ai_usage_periods.questions_used + 1,
  day_questions = (CASE WHEN ai_usage_periods.day_date = :today THEN ai_usage_periods.day_questions ELSE 0 END) + 1,
  day_date = :today, updated_at = now()
WHERE ai_usage_periods.questions_used < :monthly + ai_usage_periods.bonus_questions
  AND (CASE WHEN ai_usage_periods.day_date = :today THEN ai_usage_periods.day_questions ELSE 0 END) < :dailyCap
RETURNING questions_used, bonus_questions
```
Refused (empty RETURNING): `SELECT questions_used, bonus_questions FROM ai_usage_periods WHERE tenant_id = :tenantId AND period_month = :periodMonth`.
Usage: same INSERT shape with questions 0 / day_questions 0, `ON CONFLICT ... DO UPDATE SET input_tokens = ai_usage_periods.input_tokens + :input, output_tokens = ai_usage_periods.output_tokens + :output, updated_at = now()`.

## Deviations
1. **Time zone source**: the tenant's `timeZone` (`TenantRepository.findById`), not the outlet's. `RecordAiUsageRequest` has no `user_id`, so no outlet can be derived from the caller, and `MerchantBusinessSettingsService` writes the tenant and outlet zone together from one value (one outlet per tenant), so they are the same value. One source for both RPCs.
2. **RecordAiUsage is not owner-checked**: the packet message has no `user_id`, so only the tenant allowlist (`requireAllowedTenant`) applies. It also does not check the plan (usage is recorded whatever happened; harmless).
3. Constants live in `AiUsageRepository` (reserved path) instead of `shared/`, which was outside the reserved paths.
4. A brand-new month row is always allowed by the INSERT branch (limits are > 1), so the cap WHERE only applies on conflict.
5. Handlers now run outside a Spring transaction (the existing ones are `@Transactional(readOnly = true)`): tenant/outlet/plan reads use the shared EntityManager without one. Reads only touch basic fields, but this is not run-verified.

## Open questions / risks
- Race on the refusal reason: between the refused upsert and the follow-up SELECT a top-up could arrive; the reason is then computed from the newer row (could say DAILY_CAP_REACHED). Cosmetic.
- A tenant with no `ai_usage_periods` row and a daily cap of 0 cannot occur, so the SELECT always finds the row after a refusal.

## Existing tests affected (wave 4)
- `unit/grpc/GrpcTenantAccessPolicyTest` (line ~67) and `unit/grpc/CashierGrpcHandlerRejectionTest` (line ~81): `new GrpcTenantAccessPolicy(...)` needs a `PlanEntitlementService` argument (a mock is enough; for success paths it must return PREMIUM from `effectivePlan`). Add policy tests for `requirePremium` (FREETIER/PRO refused, PREMIUM allowed) in `GrpcTenantAccessPolicyTest`.
- `unit/grpc/CashierInsightGrpcHandlerTest`: policy is a mock, so `requirePremium` is a no-op void mock; nothing breaks. Add one case that `requirePremium` throwing FAILED_PRECONDITION is returned as is.
- `integration/grpc/GrpcSecurityIntegrationTest`, `integration/grpc/OwnerInsightIntegrationTest`: every success-path call now needs the tenant to have an active PREMIUM `subscriptions` row (or a running trial); add it to the existing fixture setup. Add the quota boundary (300 + bonus, 40/day, concurrent pair) to one of these.
- No test constructs `CashierGrpcService` directly. A new unit test for `CashierAiUsageGrpcHandler` is only needed if no IT covers refusal reasons; prefer extending `GrpcSecurityIntegrationTest`.

## R1 (orchestrator review)
- `AiUsageRepository.consumeQuestion`: removed the wildcard `List<?>`; the RETURNING result is now read with `getResultStream().findFirst().orElse(null)` into an `Object` (cast to `Object[]` when present). SQL and behaviour unchanged; unused `java.util.List` import removed. No other file touched, not compiled.
