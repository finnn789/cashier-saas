Completed architecture review of the supplied source bundle only. Memory baseline: **version 2**. No independent filesystem access, tools, tests, or changes.

Files reviewed: supplied AGENTS.md instructions, task contract, `.ai/MEMORY.md`, `.ai/ARCHITECTURE.md`, architect template, and all seven assigned Java sources.

**Observed boundaries**

- `SecurityConfiguration.java`: HTTP sessions are stateless. Account routes require `SCOPE_ACCOUNT`; platform, merchant, and cashier routes require their configured roles. Refresh/logout are anonymous at the authorization layer but require CSRF protection for POST. Other unmatched routes require authentication without a specific scope or role.
- `JwtConfiguration.java` and `TenantJwtAuthenticationConverter.java`: HS256 verification uses a Base64 key of at least 32 bytes. Decoder wiring includes issuer, audience, timestamp, and required claim validators. Conversion requires UUID user/session identifiers. ACCOUNT tokens require email and receive only `SCOPE_ACCOUNT`; MERCHANT tokens require tenant, outlet, membership, and supported roles.
- `JwtTokenService.java`: issuance separates account identity from merchant operating context. Account tokens contain no tenant/outlet/membership claims or roles. Merchant tokens carry those identifiers and roles. Token issuance does not itself authorize the supplied context.
- `AuthSessionService.java`: refresh sessions are persisted despite stateless HTTP security. Refresh tokens use 32 random bytes, stored through SHA-256 digests, and rotate transactionally. Detected reuse or expiry revokes the session; `noRollbackFor = SessionInvalidException.class` allows that revocation to commit. Merchant refresh rechecks user, tenant, membership identity/role, subscription, outlet, and outlet access. Access expiry is capped by session and subscription expiry.
- `MerchantAccessGuard.java` and `MerchantAccessRepository.java`: outlet access is checked against the principal and current database authorization. Queries constrain access, membership, and outlet to the same tenant, require active user/membership/tenant/outlet and FNB business type, and require a currently active **FREETIER** subscription. Repository misses and owner failures become `ResourceNotFoundException`.

**Material corrections and evidence limits**

1. **Session revocation is not shown to invalidate existing access JWTs immediately.** Neither conversion nor merchant guards consult session state. Logout and refresh-reuse revocation affect persisted sessions; already-issued access tokens may remain usable until expiry unless an omitted component adds enforcement.

2. **Merchant guards do not bind authorization to the token’s membership ID.** They query by user, tenant, and roles, without using `membership_id` or `session_id`. Refresh performs stronger membership binding than operational guards. Whether membership uniqueness makes this harmless requires omitted schema/entity evidence.

3. **Merchant selection validation is only partially visible.** `selectMerchant()` checks session usability, membership ownership, FNB enablement, and subscription validity/tenant match. It does not visibly check active membership/user/tenant, outlet tenant ownership, outlet activity, or outlet grants. Those checks may exist in callers or `AuthSessionEntity.selectMerchant()`; neither is supplied.

4. **The snapshot should explicitly record FREETIER-only operational access.** Refresh uses `SubscriptionEntity.isActiveAt()` without a visible plan restriction, while operational guards require `planCode = FREETIER`. Consistency depends on omitted subscription logic and intended product policy.

5. **Several snapshot claims remain qualified.** Secret loading exclusively from the environment needs `JwtProperties`/configuration evidence; audience semantics need `AudienceValidator`; outlet mismatch nondisclosure needs `TenantPrincipal.requireOutlet()`. Controllers and guard call sites are absent, so exhaustive tenant isolation cannot be established.

Proposed improvements: document access-token revocation semantics, verify selection checks across caller/entity boundaries, and align membership and subscription enforcement across refresh and operational access.

Testing: **not run by policy**. Changed files: **none**. This review does not certify end-to-end readiness.