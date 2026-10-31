# TASK-054 / 055 / 056 — Portfolio readiness (shared contract)

Created: 2026-10-31. Orchestrator memory baseline: 41. Branch: feature/ai-services (dirty tree — preserve all unrelated user changes, never revert/discard them).
User explicitly requested: make `mvnw verify` green with integration tests (testing IS authorized for TASK-054), docker-compose + demo seed (TASK-055), root README (TASK-056). Executors run in parallel; reserved writes are disjoint.

## Shared runtime contract (all tasks must conform)

| Item | Value |
|---|---|
| Compose file | `docker-compose.yml` (repo root) |
| Postgres service | `postgres`, image `postgres:16-alpine`, host port `5432`, db `cashier_saas`, user `cashier`, password env `POSTGRES_PASSWORD` (demo default `cashier_demo`) |
| Second DB for AI | `cashier_ai` created by init script in `docker/postgres/init/` |
| Backend service | `backend`, built from root `Dockerfile` (multi-stage, Maven wrapper, `-DskipTests`, JRE 17 runtime), HTTP `8080`, gRPC `9090` |
| Backend env | `CASHIER_DB_URL`, `CASHIER_DB_USERNAME`, `CASHIER_DB_PASSWORD`, `CASHIER_JWT_SECRET_B64`, `CASHIER_REFRESH_COOKIE_SECURE=false`, `SPRING_PROFILES_ACTIVE=demo` |
| Demo profile | `src/main/resources/application-demo.properties` adds Flyway location `classpath:db/demo` |
| Demo seed | `src/main/resources/db/demo/R__demo_seed.sql`, idempotent (ON CONFLICT DO NOTHING) |
| AI service | `ai-services`, built from `Ai-services/Dockerfile`, only under compose profile `ai` (`docker compose --profile ai up`) |
| Env template | root `.env.example` documents every compose variable |
| Demo accounts | owner `owner@demo.local` / `Demo123!`, cashier `cashier@demo.local` / `Demo123!` (BCrypt hashes in seed) |
| Demo data doc | `docs/demo.md` — accounts, tenant/outlet names, menu, tables, public QR tokens/URLs, sample curl flow (produced by TASK-055) |
| Swagger | `http://localhost:8080/swagger-ui.html` (verify actual path) |

## Reservations

- TASK-054 (tests): `pom.xml`, `src/test/**`. Main source changes ONLY if a real bug blocks a test — minimal, listed in report. Must not touch `src/main/resources/**`. Report: `reports/TASK-054.md`.
- TASK-055 (infra/seed): `Dockerfile`, `.dockerignore`, `docker-compose.yml`, `docker/**`, `.env.example`, `Ai-services/Dockerfile`, `Ai-services/.dockerignore`, `src/main/resources/application-demo.properties`, `src/main/resources/db/demo/**`, `src/main/resources/application.properties` (only: remove hardcoded DB password default), `docs/demo.md`. Must not touch `pom.xml`. Report: `reports/TASK-055.md`.
- TASK-056 (docs): `README.md` (root), `docs/architecture.md`. Report: `reports/TASK-056.md`.

Nobody writes `.ai/**` (orchestrator-owned). Never read or print `.env` secrets.
