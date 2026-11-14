# TASK-082 J4 report: login selection (Amendment A2)

Not compiled or tested (policy: no Maven).

## Changed files (java root `src/main/java/org/lite/project/cashiersaas/`)
- `repository/MerchantSelectionRepository.java`
- `service/LoginService.java`
- `service/AuthSessionService.java`
- `repository/MembershipOutletAccessRepository.java`
- `repository/MerchantAccessRepository.java` (only `requirePlanAccess`: `private static` -> package-private `static`)

## Contract points
- `findAccessible`: subscription left join and `Selection.subscription` removed (`Selection` is now membership + outlet). `setMaxResults(1)` stays (harmless, one row per membership/outlet).
- `LoginService.selectMerchant`: injects `SubscriptionRepository`, passes `findActiveAt(tenantId, now)` to `authSessionService.selectMerchant`. The own null/inactive check was removed (it duplicated the one in AuthSessionService).
- `AuthSessionService`: one private `entitledAccessExpiry(role, tenant, activeRows, sessionExpiry, now)`. Returns null when not entitled (owner: any active row; cashier: active non-FREETIER row or running trial), else the access-token expiry, capped at the latest `endsAt` only when no entitling row is open-ended (trial end counts). Code is J1's refresh logic moved verbatim. Used by `selectMerchant` (null -> `OperationConflictException(SUBSCRIPTION_INACTIVE)`, the same refusal as before) and `refresh` (null -> revoke + `SessionInvalidException`, as before; the subscription query is now skipped when the membership is gone). `merchantAccessExpiry` deleted. `selectMerchant` signature: last parameter is now `List<SubscriptionEntity> activeRows`.
- `listActiveChoices`: wrapped in `MerchantAccessRepository.requirePlanAccess(query, now)` (same package). Aliases match: the query's aliases come from Q objects named `tenant` and `membership`, equal to the literals the helper uses (`tenant.id`, `membership.role`, `tenant.premiumTrialStartedAt`). The hand-written subscription EXISTS and the `SUBSCRIPTION` constant/imports were removed. There was no `planCode = 'FREETIER'` filter in that query, so nothing more to drop.

## Deviations / notes
- Order of refusals in `selectMerchant`: the subscription refusal now happens inside `AuthSessionService.selectMerchant` after the session lock/usable check (previously before it in LoginService). A request with both an invalid session and no entitlement now gets the session error instead of 409. The old per-row tenant-id check in `merchantAccessExpiry` is dropped; callers pass rows fetched by tenant id.
- `requirePlanAccess` generic inference with the Blaze `selectNew(...).end()` builder type is verified only by reading, not compiled.

## Callers outside my scope that must change
- **Compile break (must fix before build):** `service/FreeTierOnboardingService.java` lines ~133 and ~154 call `authSessionService.selectMerchant(principal, ownerMembership, <outlet>, subscription)` with a single `SubscriptionEntity`. Change the last argument to `List.of(subscription)` (the FREETIER row, open-ended, owner so no cap) or to `subscriptionRepository.findActiveAt(tenantId, Instant.now())`.
- Tests (wave 4): any unit test building `LoginService` via `@InjectMocks` needs `@Mock SubscriptionRepository` and must stub `findActiveAt` instead of a `Selection` with a subscription; any test calling `new Selection(...)` / `getSubscription()` or `AuthSessionService.selectMerchant(..., SubscriptionEntity)` must follow the new signatures. Grep found no such usage in `src/test` for these identifiers (only the `selectMerchant` helper in `MerchantStaffIntegrationTest`, which goes through HTTP), but tests mocking `MerchantSelectionRepository` indirectly are not excluded. Integration cases to add: cashier login refused with no PRO/PREMIUM/trial, owner login with FREETIER + expired paid row succeeds, `GET` choices hides a cashier's outlets on FREETIER.

## A2-1: FreeTierOnboardingService callers
`service/FreeTierOnboardingService.java`: both `authSessionService.selectMerchant(...)` calls (existing-profile branch and new-profile branch) now pass `List.of(subscription)` and `java.util.List` is imported. The code only keeps the single FREETIER row in scope (the `findActiveAt` list is consumed by a stream), so no fuller list was reused. This is enough: the owner role is entitled by the open-ended FREETIER row, so no expiry cap applies. No other change. Not compiled.
