# TASK-113 T1 report (test-worker)

Test status: NOT RUN (orchestrator runs RED).

## Behaviours -> tests
- Unit `MediaStorageServiceTest`: `storesPngUnderTenantFolder`, `extensionComesFromContent` (parameterized jpg/webp/png-with-gif-name), `rejectsNonImage` (text, SVG, RIFF-not-WEBP, empty), `rejectsOverQuota`, `guardBeforeWrite`, `sweepDeletesOnlyOldUnreferenced`.
- Unit `MerchantBusinessSettingsServiceTest` (extended): `acceptsOwnMediaLogoUrl`; added `@Spy MediaProperties` + `@BeforeEach` setting base url `http://localhost:8080`.
- Integration `MediaUploadIntegrationTest`: owner upload + anonymous GET (bytes, image/png, max-age=31536000); cashier upload; cross-tenant 403/404 + nothing written; 400 (text, missing part), 401 anonymous; 413 for > 2 MB.

## Skipped (already covered)
- `rejectsForeignHttpLogoUrl`: existing `rejectsInvalidValuesBeforeMutation` case `logo-http` (`http://example.test/logo.png`) already asserts a foreign http logo is rejected.

## Notes / open questions
- 413 test uses real HTTP (`java.net.http.HttpClient` against `${local.server.port}`) because MockMvc bypasses servlet multipart limits. Needs `spring.servlet.multipart.max-file-size=2MB` plus `MaxUploadSizeExceededException` -> 413 handler.
- Unit test stubs `guard.require` leniently to return a context whose tenant has id = principal tenant, so the service may take the tenant from either; outlet/membership in that context are null.
- Unit tests assume `MediaProperties` setters `setDir/setPublicBaseUrl/setMaxTenantBytes(long)/setOrphanGrace(Duration)`, and `MediaUploadResponse.getUrl()`.
- Settings test needs `MediaProperties` as a constructor dependency (Mockito `@InjectMocks` picks up the spy).
- Integration test sets `app.media.dir` to a temp dir via `@DynamicPropertySource` (not deleted afterwards).

## Expected RED reason
- All three classes: compile error (missing `MediaProperties`, `MediaStorageService`, `MediaUploadResponse`, `FnbCatalogRepository.mediaUrls`). After compile, settings test would fail because `http://localhost...` logo is rejected as non-HTTPS; integration tests fail with 404/405 (no endpoint).
