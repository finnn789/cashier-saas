# TASK-076 W1 report (C1 staff management, C2 self password change)

Status: implemented, NOT compiled, NOT tested (policy: no Maven/tests).

## Changed files (all under src/main/java/org/lite/project/cashiersaas/ unless noted)
- entity/shared/identity/TenantMembershipEntity.java: `deactivate()` / `reactivate()` (return true when state changed; no-op otherwise).
- entity/shared/identity/StaffUserEntity.java: `changePasswordHash(String)`.
- entity/shared/audit/AuditLogEntity.java: `FNB_ORDER_CREATED("SALES_ORDER")`, `STAFF_DEACTIVATED/REACTIVATED("TENANT_MEMBERSHIP")`, `STAFF_PASSWORD_RESET("STAFF_USER")`.
- repository/AuthSessionRepository.java: `revokeForMembership(UUID, Instant)`, `revokeForUserExcept(UUID userId, UUID keepSessionId nullable, Instant)` (single method for C1 "all" with null and C2 "keep caller"). JPQL bulk update sets revokedAt + updatedAt, only rows with revokedAt null.
- repository/TenantMembershipRepository.java: `listCashiersForOutlet`, `findCashierForOutlet`, `hasActiveMembershipOutsideTenant`; shared private `cashiersForOutlet` (CASHIER role, tenant, EXISTS membership_outlet_access for outlet; did not touch MembershipOutletAccessRepository).
- service/MerchantStaffService.java (new), service/AccountPasswordService.java (new), controller/MerchantStaffController.java (new).
- controller/AuthController.java: `POST /api/v1/auth/password`.
- dto/request/identity/ChangePasswordRequest.java, dto/response/identity/MerchantStaffResponse.java, MerchantStaffPasswordResetResponse.java (new); dto/IdentityMapper.java `toStaff`.
- configuration/SecurityConfiguration.java: explicit `POST /api/v1/auth/password` `.authenticated()`.
- component/RateLimitFilter.java: path added to `AUTH_PATHS`.
- component/MemberSyncRequestedEvent.java: new `status` field (constructor gets 7th arg `String status`; toString includes status).
- service/MemberSyncPublisher.java: `memberChanged(tenant, user, role, String status)`.
- service/MemberSyncClient.java: `body.put("status", member.getStatus())`.
- Publisher call sites changed (pass `StatusConstants.ACTIVE`): CashierInvitationService (2 calls: idempotent re-claim and claim), FreeTierOnboardingService (2 calls). CashierInvitationService got a `StatusConstants` import.
- docs/contracts/member-sync.md: status sent as ACTIVE or DISABLED.

## Contract points
- C1 routes/DTO fields/sort: MerchantStaffController (list, deactivate, reactivate, password-reset); list ordered by user.fullName in TenantMembershipRepository.listCashiersForOutlet; fields in IdentityMapper.toStaff (joinedAt = membership.createdAt Instant).
- Target rule: `MerchantStaffService.requireTarget` -> `findCashierForOutlet` (tenant from principal, role CASHIER, outlet access) else `ResourceNotFoundException`; owner check via `merchantAccessGuard.requireOwner`.
- Deactivate: revoke by membership (`revokeForMembership`), audit, publish DISABLED, only when state changed; same-state call returns 200 with no audit/publish/revoke. Reactivate symmetrical with ACTIVE.
- Password reset: conflict check `hasActiveMembershipOutsideTenant` with exact Indonesian message; 12 chars from SecureRandom (letters without I/O/l, digits 2-9; position 0 letter, 1 digit, then Fisher-Yates shuffle); `changePasswordHash(passwordEncoder.encode(..))`; `revokeForUserExcept(userId, null, now)`; audit with null reason/details (no password anywhere in logs/audit).
- C2: AuthController.changePassword takes both `@AuthenticationPrincipal AccountPrincipal` and `TenantPrincipal` (the resolver returns null on type mismatch; exactly one is non-null) -> `AccountPasswordService.change(userId, sessionId, req)`: wrong current -> `InvalidCredentialsException`, same -> `InvalidOperationException("Password baru harus berbeda.")`, hash, `revokeForUserExcept(userId, callerSessionId, now)`. Response `ApiResponse.success("Password diperbarui.", null)`. ChangePasswordRequest.newPassword has the same @NotBlank/@Size(8..72) as RegisterUserRequest.password.
- Rate limit + security matcher: done.

## Deviations / notes
- `MemberSyncPublisher.memberChanged` and the `MemberSyncRequestedEvent` constructor signatures changed; existing tests break and W5 must update: MemberSyncPublisherTest (lines ~48,63,64,71), MemberSyncListenerTest (~125), MemberSyncClientTest (~109), plus an assertion that DISABLED is sent.
- Deactivate also bumps nothing on the sessions' `version`; bulk JPQL update bypasses @PreUpdate so updatedAt is set explicitly.
- Reactivate does not re-check user/tenant/subscription state; login/refresh/guard already enforce it.
- Password reset keeps the owner's own session untouched (target is never the owner).
- The membership is deactivated per tenant, but member sync is by (tenant, user), so no cross-tenant effect.

## Open questions
- None blocking. Not compiled: Blaze `fetch("membership.user")` after `innerJoin("membership.user","user")` copies the existing findMember pattern; check at test-compile.
