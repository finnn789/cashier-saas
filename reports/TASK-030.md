# TASK-030 worker report

## Checkpoint 0 — before implementation

- Acknowledged `.ai/MEMORY.md` version 5 and `tasks/TASK-030.md` reservation.
- Reviewed `PRD-MVP.md` active Pay First header and `.ai/context/settings-audit-realtime-20261007.md`.
- Dirty baseline preserved: the worktree already contains broad unrelated application, migration, test, documentation, and canonical-context changes. `AuditLogEntity.java` is already modified; TASK-030 will only add the reserved `BUSINESS_SETTINGS_CHANGED` action while preserving its existing contents.
- Exclusive write reservation: the nine Java paths, `docs/business-settings.md`, and this report listed in TASK-030. No migrations, shared repository/service changes, tests, builds, startup, database, network, or secret reads.
- Plan: add validated named DTOs and mapper; add a Blaze-only currency-use repository; add owner-guarded, tenant-locked read/update service and HTTP controller; add named entity update methods; audit changed updates atomically and skip audits for no-ops; document endpoint contract; then perform static-only review.
- Acceptance status: implementation not yet started. Tests not run by policy.

## Checkpoint 1 — HTTP contract and persistence primitives

- Added the reserved named request/response DTOs and explicit mapper. The response is null-free via `@JsonInclude(NON_NULL)`.
- Added the reserved controller with the reviewed GET/PUT route and `@Valid` full-PUT request body.
- Added named tenant/outlet settings update methods and the reserved audit action without replacing the pre-existing audit changes.
- Added the reserved Blaze-Persistence-only currency-use repository for tenant-scoped `SalesOrderEntity` and `CashierSaleEntity` detection.
- Remaining: service transaction/authorization/normalization/audit details, documentation, and static review. Tests not run by policy.

## Checkpoint 2 — transactional settings behavior

- Added the owner-guarded service. GET requires the current owner/outlet context. PUT locks the principal tenant first, then resolves the owner/outlet context in the same transaction.
- PUT normalizes optional blank strings to `null`, validates HTTPS absolute logo URLs without fetching them, validates IANA time zones, validates ISO-4217 two-fraction currency, and normalizes rates to scale two.
- Currency changes are rejected with a conflict after any tenant F&B order or cashier sale, including non-paid records. Tax/service changes affect only stored tenant defaults used by future checkouts; no order snapshots are changed.
- Changed updates atomically persist one `BUSINESS_SETTINGS_CHANGED` audit row containing escaped structured before/after settings data; no-op updates do not audit. Added endpoint behavior documentation.
- Remaining: static inspection of all reserved files and final report. Tests not run by policy.

## Checkpoint 3 — final static review

Status: **READY FOR TESTING**

### Delivered files

- `src/main/java/org/lite/project/cashiersaas/controller/MerchantBusinessSettingsController.java`
- `src/main/java/org/lite/project/cashiersaas/service/MerchantBusinessSettingsService.java`
- `src/main/java/org/lite/project/cashiersaas/repository/MerchantBusinessSettingsRepository.java`
- `src/main/java/org/lite/project/cashiersaas/dto/MerchantBusinessSettingsMapper.java`
- `src/main/java/org/lite/project/cashiersaas/dto/request/MerchantBusinessSettingsRequest.java`
- `src/main/java/org/lite/project/cashiersaas/dto/response/MerchantBusinessSettingsResponse.java`
- `src/main/java/org/lite/project/cashiersaas/entity/shared/merchant/TenantEntity.java`
- `src/main/java/org/lite/project/cashiersaas/entity/shared/merchant/OutletEntity.java`
- `src/main/java/org/lite/project/cashiersaas/entity/shared/audit/AuditLogEntity.java` (only `BUSINESS_SETTINGS_CHANGED` was added; pre-existing dirty actions retained)
- `docs/business-settings.md`
- `reports/TASK-030.md`

### Public signatures

- `GET /api/v1/merchant/outlets/{outletId}/business-settings`
- `PUT /api/v1/merchant/outlets/{outletId}/business-settings`
- `MerchantBusinessSettingsService.get(TenantPrincipal, UUID)`
- `MerchantBusinessSettingsService.update(TenantPrincipal, UUID, MerchantBusinessSettingsRequest)`
- `MerchantBusinessSettingsRepository.hasCurrencyUse(UUID)`

### Acceptance and static evidence

- Owner/current-outlet access: both operations use `MerchantAccessGuard.requireOwner`; tenant/outlet identity comes only from the authenticated principal plus guarded path outlet.
- Atomic update: PUT is transactional, locks the tenant with existing `FnbOrderRepository.lockTenant`, then resolves the guarded context, changes attached tenant/outlet entities, and persists the audit entry in that transaction.
- Validation: Bean validation covers required fields, lengths, rate range/precision, and uppercase currency shape. Service validation covers ISO-4217 two-fraction currencies, IANA zone IDs, and optional HTTPS absolute logo URLs. Optional blank values clear to `null`.
- Currency: the reserved repository uses Blaze-Persistence only and scopes both `SalesOrderEntity` and `CashierSaleEntity` checks by tenant. A currency change after either kind of row is rejected; no other data is modified.
- Rates: tax/service values are stored for future checkout pricing only. No order snapshot mutation exists in this slice.
- Audit: changed updates write escaped structured `before`/`after` JSON in `BUSINESS_SETTINGS_CHANGED`; no-op PUTs create no audit row. No secrets or credentials are included.
- API contract: named public DTO classes and explicit mapper are used; the response omits null properties; controller returns `ApiResponse` directly and exposes no persistence entity.
- Scan: no `ResponseEntity`, wildcard generic (`<?>`, `? extends`, `? super`), request-supplied tenant ownership, or non-Blaze query execution in TASK-030 files. No writes outside the task allowlist were made by this worker.

### Verification status and risks

- Tests/build/startup/database/network operations: **not run by policy**.
- Static checks only: reviewed reserved-file diff/content and current dirty worktree. Existing concurrent/unrelated changes, including the pre-existing `AuditLogEntity` additions and TASK-031/TASK-032 paths, were preserved.
- Recommended later tests: owner GET/PUT success; non-owner and cross-tenant/outlet denial; required/format/rate validation; blank optional clearing and null omission; no-op audit suppression; changed audit before/after data; currency allowed before activity and rejected after F&B/unpaid/cancelled or cashier-sale activity; tax/service changes leave existing order snapshots unchanged.

## Correction checkpoint 4 — reviewed hardening plan

- Read `tasks/TASK-030-REVIEW.md` against the current TASK-030 submission and retained memory5/task allowlist constraints.
- Preserve the current dirty baseline and concurrent TASK-031/TASK-032 work. No files outside the unchanged TASK-030 allowlist will be written.
- Corrections planned: reject logo URL credentials and invalid explicit ports; emit valid JSON for every C0 control character in audit details; repeat required-name/rate validation in service normalization and map rounding errors to `InvalidOperationException`; compare rates numerically rather than by `BigDecimal` scale to suppress false-change audit events.
- Tests/build/startup/database/network remain prohibited. Static review follows the edits.

## Correction checkpoint 5 — hardening implemented

- `validateLogoUrl` now rejects user-info/credential URLs and invalid explicit ports. Audit serialization independently excludes any credential-bearing legacy logo URL.
- Audit JSON escaping now encodes every U+0000 through U+001F code unit as a JSON unicode escape, including backspace and form feed, while preserving quote and backslash escaping.
- Service normalization now independently checks post-trim business/outlet names, rate presence/range/scale, and translates any scale-normalization failure to `InvalidOperationException`.
- Tenant rate comparison now uses `BigDecimal.compareTo`, so numerically unchanged values such as stored `0` and requested `0.00` do not create an audit event. Actual changes keep the normalized two-decimal rate values.
- Documentation now states the credential and port restrictions. Remaining: static-only source/diff review. Tests not run by policy.

## Correction checkpoint 6 — final static review

Status: **READY FOR TESTING**

- URL validation evidence: non-null `URI.getUserInfo()` rejects credential-bearing URLs; port validation permits only unspecified (`-1`) or `1..65535`; HTTPS absolute host validation remains. `auditLogoUrl` independently omits a legacy credential-bearing URL from before/after audit details.
- JSON evidence: `escape` loops over every character and writes `\\u00XX`-style escapes for all U+0000..U+001F characters, including U+0008 and U+000C; quotes and backslashes remain escaped. No dependency was added.
- Defensive service validation evidence: `requiredName` trims then checks nonblank/120-char limits; `rate` checks non-null, `0..100`, scale <=2, normalizes to scale two, and converts `ArithmeticException` to `InvalidOperationException`.
- No-op evidence: `TenantEntity.ratesDiffer` uses `BigDecimal.compareTo`, while real changes assign the normalized scale-two values. Therefore stored `0` versus requested `0.00` is not audited as a change.
- Preserved behavior: owner guard, tenant lock, tenant-scoped order/sale currency-history guard, and atomic audit persist are unchanged.
- Static checks: inspected corrected service/entity/docs/report, scanned TASK-030 sources for wildcard generics and `ResponseEntity`, and ran whitespace-only `git diff --check`; no task-local issue reported. The broad dirty worktree and concurrent TASK-031/TASK-032 files were not edited.
- Tests/build/startup/database/network operations: **not run by policy**.
- Recommended later focused tests: reject `https://user:pass@example.test/logo.png`, port `0`, and port `65536`; allow valid HTTPS/default and in-range port URLs; confirm C0 characters serialize as valid audit JSON; direct service calls reject blank post-trim names and rate scale/range violations with 400 mapping; confirm `0` to `0.00` is a no-op with no audit row.
