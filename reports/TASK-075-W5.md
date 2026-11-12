# TASK-075-W5

Regression: `GET /api/v1/public/plans` (MembershipPlanCatalogController) became 401 after TASK-074 removed the public permitAll.

## Changes
- `SecurityConfiguration.securityFilterChain`: added `.requestMatchers(HttpMethod.GET, "/api/v1/public/plans").permitAll()` directly after the swagger/api-docs permitAll line (unchanged). GET only, exact path, no wildcard.
- `SecurityAuthorizationIntegrationTest`: new test `publicPlanCatalogIsAnonymousGetOnlyWhileOtherPublicPathsStayProtected` placed after `removedPublicAndRealtimeRoutesAreProtectedLikeAnyOtherApiPath`: anonymous GET `/api/v1/public/plans` expects 200 (service returns a static list FREETIER/PREMIUM/PRO, no DB data needed); anonymous GET `/api/v1/public/fnb/menu/x` expects 401. The existing test is untouched.

## Test status
Tests not run by policy.
