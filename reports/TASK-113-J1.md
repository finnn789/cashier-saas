# TASK-113 J1 report

Status: DONE (code written, not compiled). Baseline: 32e837c.

New: MediaController, MediaStorageService, MediaOrphanSweepScheduler, MediaProperties, MediaResourceConfiguration, MediaUploadResponse.
Modified: FnbCatalogRepository (mediaUrls), MerchantBusinessSettingsService (D9, MediaProperties dep), SecurityConfiguration (GET /media/** permitAll), GlobalExceptionHandler (413 via HttpStatus.CONTENT_TOO_LARGE, missing part 400), application.properties, docker-compose.yml, Dockerfile, .gitignore, .env.example.

Contract points: D1-D10 done. Tenant id from principal.getTenantId(); guard called first.

Deviations:
- mediaUrls uses two JPQL queries concatenated (HQL UNION avoided), still tenant scoped.
- Quota message hardcodes "50 MB" as in the packet even if maxTenantBytes is changed.
- HttpStatus.CONTENT_TOO_LARGE assumed available (Spring 7); if compile fails use PAYLOAD_TOO_LARGE.

Existing tests to update: any test constructing MerchantBusinessSettingsService manually (settings unit test already handled by T1). Integration tests need app.media.dir writable (default ./data/media, created on demand; resource location need not exist at startup).

Open question: default `./data/media` resource handler when dir missing at startup returns 404 until first upload (Spring resolves lazily) - expected.

Test status: NOT RUN (orchestrator runs GREEN).
