# TASK-057 W3 report: compose, env and docs

Scope: compose/env/docs only. No Java, Python or test changes. No commit. No docker run, no tests.

## Files changed

1. `docker-compose.yml`
   - Header comments: mention that `CASHIER_GRPC_SERVICE_KEY` is mandatory and that gRPC 9090 is internal only.
   - `backend`: removed host mapping `${BACKEND_GRPC_PORT:-9090}:9090`; added `expose: ["9090"]`. Kept the HTTP port mapping.
   - `backend` env: added `CASHIER_GRPC_SERVICE_KEY: ${CASHIER_GRPC_SERVICE_KEY:?set CASHIER_GRPC_SERVICE_KEY in .env (see .env.example)}` and `CASHIER_GRPC_ALLOWED_TENANT_IDS: ${AI_TENANT_ID:-00000000-0000-4000-8000-0000000000b1}`.
   - `ai-services` env: added the same `CASHIER_GRPC_SERVICE_KEY` line.
   - Everything else unchanged.

2. `.env.example` (root)
   - Added `CASHIER_GRPC_SERVICE_KEY=` (empty, under Required) with a comment: shared secret, at least 16 random characters, generation commands (`openssl rand -base64 32` and the PowerShell equivalent).
   - Documented `CASHIER_GRPC_ALLOWED_TENANT_IDS` as a comment only, under the AI section next to `AI_TENANT_ID` and in the local-run section. It is intentionally not an assignment: compose derives the backend value from `AI_TENANT_ID`, so an uncommented empty line would only add a misleading deny-all override for local runs.
   - Removed the `BACKEND_GRPC_PORT` host-port setting is NOT done: the line remains in the file. See "Not done / notes".

3. `Ai-services/.env.example`
   - Added `CASHIER_GRPC_SERVICE_KEY=` with a comment: must equal the backend value, sent as metadata `x-internal-service-key`, never commit the real value.

4. `docs/demo.md`
   - Start section: added a second `openssl rand -base64 32` line for `CASHIER_GRPC_SERVICE_KEY`.
   - Service table: gRPC row now says it is not published to the host, internal network only (`backend:9090`), and requires `x-internal-service-key` = `CASHIER_GRPC_SERVICE_KEY`.

5. `README.md`
   - Quick start: the comment now says both `CASHIER_JWT_SECRET_B64` and `CASHIER_GRPC_SERVICE_KEY` are required.
   - Service table: gRPC row changed from `localhost:9090 (override: BACKEND_GRPC_PORT)` to not published to the host, internal only, caller must send `CASHIER_GRPC_SERVICE_KEY`.
   - Environment variable table: added rows for `CASHIER_GRPC_SERVICE_KEY` (required, at least 16 characters) and `CASHIER_GRPC_ALLOWED_TENANT_IDS` (empty denies all).
   - Known limitations: replaced the "no authentication layer / publishes 9090" bullet with a statement that gRPC has a service key and tenant allowlist, that `tenant_id` is still a request field, that 9090 is not published to the host, and that token-bound tenant identity is a follow-up.

6. `reports/TASK-057-W3.md` (this file).

## Validation

- `docker compose config` (no profile), with dummy inline values for `CASHIER_JWT_SECRET_B64` and `CASHIER_GRPC_SERVICE_KEY` only (not saved anywhere): exit 0. Output shows `expose: 9090`, no published 9090, and `CASHIER_GRPC_ALLOWED_TENANT_IDS` resolving to the demo tenant default.
- `docker compose --profile ai config`, same dummy values: exit 0. `ai-services` shows `CASHIER_GRPC_SERVICE_KEY`.
- Without `CASHIER_GRPC_SERVICE_KEY` (env var unset): exit 1, so the `:?` guard fires. The check ran in an environment that might still read a local `.env`; the exit code was 1, consistent with the guard but not separately confirmed against a `.env`-free setup.
- Python YAML parse of `docker-compose.yml` (`yaml.safe_load`): ok.
- No real `.env` file was read or modified. No docker daemon command was run; `docker compose config` does not need the daemon.

## Not done / notes

- The root `.env.example` still contains `BACKEND_GRPC_PORT=9090`. It is now unused by compose (no host mapping for gRPC). It was not removed because the contract for this wave did not ask for it and removal is a judgment call for the orchestrator. Recommend removing it in a follow-up, or leaving it as harmless. Also `README.md` no longer mentions `BACKEND_GRPC_PORT`.
- `README.md` line 28 and the architecture diagram line still mention port 9090 as the internal gRPC port. They are accurate for the internal network and were left unchanged.
- The `ai-services` Docker build passes `CASHIER_GRPC_SERVICE_KEY` into the container environment. The Python client change (W2) must read this exact variable name.
- Java side (W1) must read `CASHIER_GRPC_SERVICE_KEY` and `CASHIER_GRPC_ALLOWED_TENANT_IDS` as named in the shared contract.
