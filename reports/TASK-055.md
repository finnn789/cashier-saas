# TASK-055 report - one-command local demo

## Changed / created files
- `Dockerfile` (multi-stage: temurin 17 JDK + `./mvnw -DskipTests package`, CRLF/exec-bit fix via sed/chmod; temurin 17 JRE runtime, non-root `app`, EXPOSE 8080 9090, bash /dev/tcp healthcheck on `/v3/api-docs`)
- `.dockerignore`
- `docker-compose.yml` (postgres, backend, ai-services under profile `ai`)
- `docker/postgres/init/01-create-ai-db.sql` (creates `cashier_ai`)
- `.env.example` (rewritten: every compose variable, JWT generation commands)
- `Ai-services/Dockerfile`, `Ai-services/.dockerignore` (python:3.12-slim, `uv sync --frozen --no-dev`, `alembic upgrade head && uvicorn app.application:app --host 0.0.0.0 --port 8001`)
- `src/main/resources/application-demo.properties`
- `src/main/resources/db/demo/R__demo_seed.sql`
- `src/main/resources/application.properties` (only: `CASHIER_DB_PASSWORD` default `shadowclasher` -> empty)
- `docs/demo.md`, `reports/TASK-055.md`

## Deviations from contract
- Postgres image is `postgres:17-alpine`, not 16 (coordinator instruction; V1 is a pg_dump 18 file using `SET transaction_timeout`, PG16 fails with "unrecognized configuration parameter"). I also verified 18-alpine works; final config is 17 and was re-verified.
- `business_types` has no seed in migrations; the demo seed inserts `FNB`.
- `main.py` binds 127.0.0.1, so the AI image runs uvicorn directly on 0.0.0.0.
- QR tokens are stored plain (`table_qr_tokens.token`, regex `[A-Za-z0-9_-]{43}` enforced by PublicQrAccessService), so seeded plain tokens work.

## Verification (docker compose with a scratch env file outside the repo; host port 8080 was taken by another user container `flowable-ui`, so BACKEND_HTTP_PORT=8088 was used)
- `docker compose up -d --build postgres backend`: images built (protobuf plugin works on Linux); backend became `healthy`; Flyway applied V1, V2 and the repeatable demo seed.
- PG16 attempt failed as described above (evidence for the PG17+ requirement).
- Swagger: `/swagger-ui.html` 302 -> `/swagger-ui/index.html` 200; `/v3/api-docs` 200.
- GET public menu by token 200 (3 categories, 6 products with option groups).
- POST public QR order (Idempotency-Key >= 16 chars) 201.
- POST /auth/login owner 200 (nextStep SELECT_MERCHANT_OUTLET); GET /auth/outlets 200; POST /auth/select-merchant 200 (OPERATE, OWNERMERCHANT). Same for cashier.
- GET products 200; GET tables 200 (3 tables with plain tokens).
- POST POS order 201; POST payments CASH 200 (change 116480.00, receipt number returned); kitchen list/prepare/ready/complete all 200.
- GET sales report 200 after rebuilding with the fixed `FnbSalesReportRepository` (first run before the fix returned 500 "No matching constructor"; CSV endpoint was 200 throughout). Summary showed CASH netSales 144000 over 2 transactions, tax 11%, service 5% applied.
- Seed re-run twice via psql: all `INSERT 0 0`, 2 staff users, no errors (idempotent). DBs `cashier_saas` and `cashier_ai` both exist.
- Final re-verification on `postgres:17-alpine`: healthy, login/order/pay/kitchen/report all OK (PostgreSQL 17.11).
- `docker compose --profile ai build ai-services` succeeded (image built; not run - needs Telegram/OpenRouter keys).
- `docker compose config -q` OK. `docker compose down -v` executed; only the user's pre-existing containers remain.

## Known limitations
- AI service container was only built, not started; it needs real Telegram/OpenRouter values and `AI_MODEL`.
- Compose uses the bundled Maven wrapper online; first build downloads dependencies (several minutes).
- If host port 8080 is occupied, set `BACKEND_HTTP_PORT` in `.env`.
- Hash for `Demo123!` was generated with spring-security-crypto BCryptPasswordEncoder; login succeeded against it for both accounts.
- Real `.env` files were not read.
