# TASK-113: Image upload to local disk (product photo + tenant logo)

Status: PLANNED. Memory version: 104. Branch `feature/leakage-report`. Baseline commit: `32e837c` (dirty: none). UI repo `E:\Java\cashier-saas-ui` is not git.

## User instruction (2026-11-26)
"Buatkan API untuk upload gambar ke DISK" (no object storage). Approved: random UUID file names, plain public URL (no presigned URL), 2 MB limit, JPEG/PNG/WebP by magic bytes, tenant quota, daily orphan sweep with 24h grace, browser-side WebP compression, logo upload in settings, product photo in the product form.

## Global rules (every worker)
- Reports: `reports/TASK-113-<id>.md`.
- No Flyway migration: `products.image_url` and `tenants.logo_url` (varchar 512) already exist and keep storing the absolute URL.
- Reuse: `MerchantAccessGuard.require(principal, outletId, Permission.CATALOG_EDIT)`, `InvalidOperationException` (400), `OperationConflictException` (409), `ApiResponse.created`, `GlobalExceptionHandler`, `SchedulingConfiguration` (`@EnableScheduling` already on), `DailySummaryScheduler` as the scheduler pattern, `RateLimitProperties` as the `@ConfigurationProperties` pattern, `FnbCatalogRepository` (EntityManager class) for the reference query.
- No new dependency (backend or UI). No interface/abstraction for storage.

## Orchestrator design decisions (reversible, user may override)
- D1 Storage path: `{app.media.dir}/{tenantId}/{uuid}.{ext}`; `uuid` = `UUID.randomUUID()`; `ext` from the detected content, never from the client file name.
- D2 Allowed types by magic bytes only: JPEG `FF D8 FF` -> `jpg`; PNG `89 50 4E 47 0D 0A 1A 0A` -> `png`; WebP bytes 0-3 `RIFF` and 8-11 `WEBP` -> `webp`. Anything else (incl. SVG, GIF, empty file) -> `InvalidOperationException("File harus berupa gambar JPEG, PNG, atau WebP.")` (400).
- D3 Size: `spring.servlet.multipart.max-file-size=2MB`, `max-request-size=3MB`. `MaxUploadSizeExceededException` -> 413 in `GlobalExceptionHandler`. Missing `file` part (`MissingServletRequestPartException`) -> 400.
- D4 Tenant quota: sum of file sizes in `{dir}/{tenantId}/` + new file > `app.media.max-tenant-bytes` (default 52428800 = 50 MB) -> `OperationConflictException("Kuota penyimpanan gambar penuh (50 MB).")` (409), nothing written. (Changed from the earlier "413" idea to reuse an existing exception.)
- D5 URL returned and stored: `{app.media.public-base-url}/media/{tenantId}/{uuid}.{ext}` (base without trailing slash). Public, no auth, unguessable name.
- D6 Serving: `WebMvcConfigurer.addResourceHandlers("/media/**")` -> `file:{dir}/`, cache 365 days (`Cache-Control: max-age=31536000, public`); `GET /media/**` permitAll in `SecurityConfiguration`. Spring's resource handler blocks path traversal.
- D7 Write is atomic: write to a temp file in the tenant folder, then `Files.move(..., ATOMIC_MOVE)`; tenant folder created on demand.
- D8 Orphan sweep: `@Scheduled(cron = "${app.media.orphan-sweep-cron:0 30 3 * * *}")`, `@ConditionalOnProperty(app.media.orphan-sweep.enabled, matchIfMissing=true)`. For every folder under `{dir}` whose name parses as UUID: referenced file names = last path segment of every URL from `FnbCatalogRepository.mediaUrls(tenantId)`; delete regular files whose last-modified is older than `now - app.media.orphan-grace` (default `PT24H`) and whose name is not referenced. Ignore non-UUID folders and non-regular files. Failure of one file is logged and skipped. Archived products still reference their image, so it is kept. Copied menus share one URL, so it is kept while any product references it.
- D9 Logo validation (`MerchantBusinessSettingsService.validateLogoUrl`): also accept any URL that starts with `{public-base-url}/media/` (may be `http` in dev). All other rules unchanged. Product `imageUrl` validation unchanged.
- D10 Who can upload: `Permission.CATALOG_EDIT` (owner + cashier) on both `/merchant/...` and `/cashier/...` paths; saving a logo stays owner-only through the existing settings PUT.
- D11 UI: compress in the browser before upload (canvas, longest side 1024 px, `image/webp` quality 0.8). If the browser cannot encode WebP or the result is larger than the original, upload the original. Reject client-side > 2 MB after compression with the same message as the server.

## Contracts shared between workers
- REST: `POST /api/v1/merchant/outlets/{outletId}/media/images` and `POST /api/v1/cashier/outlets/{outletId}/media/images`, `multipart/form-data`, part name `file`. `201 ApiResponse<MediaUploadResponse>` with `data.url` (string, absolute). Errors: 400 bad type/missing part, 401 anonymous, 403/404 per guard (other tenant outlet), 409 quota, 413 > 2 MB.
- `GET /media/{tenantId}/{uuid}.{ext}`: public, image bytes, Content-Type from extension, `Cache-Control` with `max-age=31536000`.
- `MediaProperties` (`configuration/`, `@ConfigurationProperties(prefix="app.media")`, Lombok getters/setters like `RateLimitProperties`): `String dir`, `String publicBaseUrl`, `long maxTenantBytes`, `Duration orphanGrace`.
- `MediaStorageService` (`service/`), constructor deps exactly `(MerchantAccessGuard guard, MediaProperties properties, FnbCatalogRepository catalog)`:
  - `MediaUploadResponse storeImage(TenantPrincipal principal, UUID outletId, MultipartFile file)`
  - `int sweepOrphans(Instant now)` -> number of deleted files
- `FnbCatalogRepository.mediaUrls(UUID tenantId): List<String>` — non-null `products.image_url` of that tenant plus non-null `tenants.logo_url` of that tenant.
- `MediaUploadResponse` (`dto/response/`, Lombok class, field `String url`).
- `MerchantBusinessSettingsService` gains a `MediaProperties` constructor dependency (for D9).

## Wave 0 — RED (`test-worker`, before any production code)

### T1 (`test-worker`)
Reserved:
- `src/test/java/org/lite/project/cashiersaas/unit/service/MediaStorageServiceTest.java` (new)
- `src/test/java/org/lite/project/cashiersaas/unit/service/MerchantBusinessSettingsServiceTest.java` (extend; add `@Mock MediaProperties` or a real instance as the new constructor arg)
- `src/test/java/org/lite/project/cashiersaas/integration/fnb/MediaUploadIntegrationTest.java` (new, extends `AbstractPostgresIntegrationTest`, reuse `ApiTestSupport`/`MerchantFixture`; set `app.media.dir` to a `@TempDir`/temp path via `@DynamicPropertySource` or test properties)
Do: write the cases under "Test cases". Use small byte arrays with the right magic bytes; set `maxTenantBytes` small in the unit test instead of writing 50 MB. Files' last-modified via `Files.setLastModifiedTime`. They must fail (compile or assertion) until Wave 1.

## Wave 1 — GREEN (parallel, after RED is confirmed; never edit the Wave 0 tests)

### J1 — backend (`java-worker`)
Reserved:
- `src/main/java/org/lite/project/cashiersaas/controller/MediaController.java` (new)
- `src/main/java/org/lite/project/cashiersaas/service/MediaStorageService.java` (new)
- `src/main/java/org/lite/project/cashiersaas/component/MediaOrphanSweepScheduler.java` (new)
- `src/main/java/org/lite/project/cashiersaas/configuration/MediaProperties.java` (new)
- `src/main/java/org/lite/project/cashiersaas/configuration/MediaResourceConfiguration.java` (new, `WebMvcConfigurer`)
- `src/main/java/org/lite/project/cashiersaas/dto/response/MediaUploadResponse.java` (new)
- `src/main/java/org/lite/project/cashiersaas/repository/FnbCatalogRepository.java` (add `mediaUrls` only)
- `src/main/java/org/lite/project/cashiersaas/service/MerchantBusinessSettingsService.java` (D9 only)
- `src/main/java/org/lite/project/cashiersaas/configuration/SecurityConfiguration.java` (permitAll `GET /media/**` only)
- `src/main/java/org/lite/project/cashiersaas/exception/GlobalExceptionHandler.java` (413 + missing part 400 only)
- `src/main/resources/application.properties` (`app.media.*` with `CASHIER_MEDIA_*` env vars: `CASHIER_MEDIA_DIR` default `./data/media`, `CASHIER_MEDIA_PUBLIC_BASE_URL` default `http://localhost:8080`, `CASHIER_MEDIA_MAX_TENANT_BYTES` default `52428800`, `CASHIER_MEDIA_ORPHAN_GRACE` default `PT24H`, sweep enabled/cron; plus multipart limits)
- `docker-compose.yml` (backend: named volume `media:/app/data/media`, env `CASHIER_MEDIA_DIR=/app/data/media`, `CASHIER_MEDIA_PUBLIC_BASE_URL` default `http://localhost:8081`; declare volume `media`)
- `Dockerfile` (create `/app/data/media` owned by user `app` before `USER app`)
- `.gitignore` (add `data/`)
- `.env.example` if it exists (document the `CASHIER_MEDIA_*` vars)
Do: D1–D10 and the contracts. Controller thin (`@PostMapping(value={...}, consumes=MediaType.MULTIPART_FORM_DATA_VALUE)`, `@RequestPart("file") MultipartFile file`, `ApiResponse.created("Gambar diunggah.", ...)`). Scheduler wraps the call in try/catch and logs like `DailySummaryScheduler`. `mediaUrls` as one native/JPQL query (UNION of both columns) scoped to the tenant.
Acceptance: all T1 tests GREEN; `mvnw.cmd -B -Punit-tests test-compile` exit 0; no file written outside `{dir}/{tenantId}/`.

### F1 — frontend (`fe-worker`, repo `E:\Java\cashier-saas-ui`; may run in parallel with T1)
Reserved:
- `src/api/media.api.ts` (new): `useUploadImage(outletId)` React Query mutation; POST `FormData` with part `file` through the existing axios client in `src/api/client.ts` (path `/merchant/outlets/{outletId}/media/images` for owner, `/cashier/...` for cashier, choose the same way `product.api.ts` chooses merchant vs cashier paths); returns `url`. Do not set `Content-Type` manually (axios sets the multipart boundary).
- `src/components/common/ImageUploadField.tsx` (new): props `value?: string`, `onChange(url: string)`, `outletId`, `label`, `hint`; shows preview (`<img>` with alt), choose/replace and remove buttons, loading + error state (server message), `<input type="file" accept="image/jpeg,image/png,image/webp">` visually hidden but keyboard reachable; compression per D11 inside this file.
- `src/pages/products/components/ProductFormPanel.tsx`: add the field bound to `imageUrl` (existing payload already sends it), reuse existing language keys `imageUrlLabel` / `imagePreview` / `imagePreviewDesc` in the products section.
- `src/pages/settings/components/SettingsForm.tsx`: replace the `logoUrl` text input with the field; reuse `receiptLogoLabel` / `uploadMonochromeLogo`; keep the field optional (remove sets it to empty -> current "clear" behaviour of the form).
- `src/language/id.ts`, `src/language/en.ts`: add only missing keys (upload, replace, remove, uploading, too large, wrong type); fix `logoUploadAlert` text to "maks. 2 MB".
Acceptance: `tsc -b` exit 0, oxlint no new warnings in changed files, vitest stays green. No new dependency.

## Test cases (TDD - written and RED before any production code)
Unit `MediaStorageServiceTest` (`@TempDir`, mocks `MerchantAccessGuard`, `FnbCatalogRepository`; real `MediaProperties` with `publicBaseUrl=http://localhost:8080`):
- stores PNG: file at `{dir}/{tenantId}/{uuid}.png` with the same bytes; url = `http://localhost:8080/media/{tenantId}/{uuid}.png` (`storesPngUnderTenantFolder`)
- JPEG -> `.jpg`, WebP -> `.webp`; client name `x.gif` with PNG bytes -> `.png` (`extensionComesFromContent`)
- text bytes named `a.png`, SVG bytes, empty file -> `InvalidOperationException`, tenant folder has no file (`rejectsNonImage`)
- existing files + new file over `maxTenantBytes` -> `OperationConflictException`, no new file (`rejectsOverQuota`)
- calls `guard.require(principal, outletId, Permission.CATALOG_EDIT)`; when guard throws, nothing written (`guardBeforeWrite`)
- `sweepOrphans(now)`: old unreferenced deleted; old referenced by product URL kept; old referenced by logo URL kept; unreferenced younger than grace kept; non-UUID folder untouched; returns deleted count (`sweepDeletesOnlyOldUnreferenced`)
Unit `MerchantBusinessSettingsServiceTest`:
- logo `http://localhost:8080/media/{t}/{u}.png` accepted; `http://evil.example/x.png` still rejected (`acceptsOwnMediaLogoUrl`, `rejectsForeignHttpLogoUrl`)
Integration `MediaUploadIntegrationTest`:
- owner POST merchant path PNG -> 201, `data.url`; anonymous GET of that URL path -> 200, same bytes, `Content-Type: image/png`, `Cache-Control` contains `max-age=31536000`
- cashier POST cashier path -> 201
- owner of tenant A uploading to tenant B outlet -> 403 or 404 (whatever the guard returns elsewhere), nothing written
- file > 2 MB -> 413; text file -> 400; missing `file` part -> 400; anonymous POST -> 401
- RED evidence (orchestrator): `mvnw.cmd -B -Punit-tests test -Dtest=MediaStorageServiceTest,MerchantBusinessSettingsServiceTest` -> fails (compile: missing classes)
- GREEN evidence (orchestrator): same command passes; `MediaUploadIntegrationTest` runs with Docker Desktop up.
