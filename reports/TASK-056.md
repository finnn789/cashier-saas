# TASK-056 - README and architecture docs

## Files written
- `README.md` (repo root)
- `docs/architecture.md`
- `reports/TASK-056.md`

No other files touched; no git state changes; `.env` files not read.

## Sources checked
Contract `tasks/TASK-054-056-PORTFOLIO.md`, `.ai/ARCHITECTURE.md`, `AGENTS.md`, `pom.xml`, `application.properties`, `cashier_service.proto`, `Ai-services/README.md` and `pyproject.toml`; source: `SecurityConfiguration`, `FnbPosOrderService`, `FnbPaymentService`, `FnbOrderRepository.lockTenant` (and all its call sites), `AuthSessionService.refresh`, `RefreshCookieService`, `FnbRealtimeTicketService`, `FnbRealtimeHandshakeInterceptor`, `FnbOrderChangedListener`, `FnbWebSocketConfiguration`, controller route listing, enums, migration file list.

## Verified in source
Idempotency key format and fingerprint (SHA-256, sorted options, conflict on mismatch); tenant lock applied in checkout, payment, kitchen, discount, cancellation, tables, settings, mock gateway; refresh rotation with session revoke on reuse; cookie HttpOnly/SameSite=Strict/path; ticket TTL 30 s, caps, subprotocol handshake, query-string rejection; AFTER_COMMIT listener, executor 1/2/queue 1000; role/path policy; Swagger path `/swagger-ui.html` is permitted in security config and springdoc is a dependency.

## Not verified / caveats stated in docs
- Swagger UI path: assumed springdoc default `/swagger-ui.html` (no custom property found); not run.
- `docker-compose.yml`, `.env.example`, `docs/demo.md`, demo profile and seed are produced by other executors; README follows the shared contract and was not checked against them. `openssl rand -base64 32` is a suggested command, not from the repo.
- Postgres 16 badge comes from the contract image `postgres:16-alpine`.
- "Kitchen retries not idempotent", "idempotent replays do not emit events" and the PRD-vs-code role difference come from `.ai/ARCHITECTURE.md` (docs/architecture.md repeats the first two); not re-traced in code.
- "Public order status token" and the public QR flow structure were taken from controller routes and architecture notes, `PublicOrderService` internals not fully read.
- No `/api/v1/platform` controller exists, though the SUPERADMIN rule does.
- No gRPC auth interceptor was found (grep in `grpc/`); stated as a limitation, not as a proven vulnerability.
- `spring.jpa.open-in-view=true` in current properties, contradicting `.ai/ARCHITECTURE.md`; docs state the property value.
- No test counts, metrics or CI badges claimed. `./mvnw verify` with Testcontainers is per the task statement; test setup itself is TASK-054's.

## Revision 2
Read `docker-compose.yml`, `.env.example`, `docs/demo.md`, `reports/TASK-054.md`, `reports/TASK-055.md` and aligned the docs.
- README: Postgres badge 16 -> 17; PG17+ stated in local-dev section; quick start now uses `up -d --build`, `--profile ai up -d --build`, `down -v`, PowerShell secret command, first-build note; ports table with override variables (`BACKEND_HTTP_PORT`, `BACKEND_GRPC_PORT`, `POSTGRES_HOST_PORT`, `AI_HTTP_PORT`), Swagger redirect and `/v3/api-docs`; demo tenant/outlet/accounts and login flow; tests section now lists what the integration suite proves and cites 200 unit / 10 integration passing from TASK-054; limitations add compose-publishes-9090, AI container not runtime-verified, test gaps (CASHIER role, MOCK_GATEWAY); `business_types` FNB note.
- docs/architecture.md: PG17+ note, gRPC publish caveat.
- Sources of new claims: compose/env/demo.md/TASK-054/TASK-055 reports; I did not run Docker or the tests myself. Resolved earlier unverified item: Swagger path confirmed by TASK-055 report.
