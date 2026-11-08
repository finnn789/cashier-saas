# TASK-059-D report (config and docs for AI_SERVICE_BASE_URL / AI_INTERNAL_API_KEY)

Status: COMPLETE for Section D scope (text and config only). No source code, tests or docs/contracts touched. No builds run. No git add/commit. Real `.env` files were not read.

## Changed files

| File | Change |
|---|---|
| `.env.example` | Added `AI_INTERNAL_API_KEY` (placeholder, distinct from gRPC key) and `AI_SERVICE_BASE_URL` (blank) with comments. `AI_TENANT_ID` marked as development fallback. Removed the `CASHIER_GRPC_ALLOWED_TENANT_IDS` comment; replaced with the database tenant-check note. |
| `Ai-services/.env.example` | Added `AI_INTERNAL_API_KEY` (placeholder, must equal backend key, differs from gRPC key). `AI_TENANT_ID` changed from "required" to optional development fallback. |
| `docker-compose.yml` | Header lists `AI_INTERNAL_API_KEY` as mandatory. `backend`: removed `CASHIER_GRPC_ALLOWED_TENANT_IDS`; added `AI_SERVICE_BASE_URL: ${AI_SERVICE_BASE_URL:-http://ai-services:8001}` and `AI_INTERNAL_API_KEY: ${AI_INTERNAL_API_KEY:?...}`. `ai-services`: added `AI_INTERNAL_API_KEY` (`:?` style); `AI_TENANT_ID` commented as fallback. YAML re-parsed successfully with PyYAML. |
| `README.md` | Registration phone requirement; diagram edge for in-progress staff sync; quick start lists `AI_INTERNAL_API_KEY`; new subsection "Staff identity for the Telegram assistant (in progress)"; local env table: removed allowlist row, added `AI_SERVICE_BASE_URL` and `AI_INTERNAL_API_KEY`; gRPC roadmap item rewritten as database tenant check; added in-progress roadmap item. |
| `docs/demo.md` | Start section: third `openssl` line for `AI_INTERNAL_API_KEY`; gRPC row states database tenant check; notes for in-progress staff sync and Telegram identification. |
| `docs/architecture.md` | Section 6 caveat rewritten (service key interceptor + DB tenant check; removed the stale "no interceptor" and "publishes 9090" claims). New section 8 "Staff sync and Telegram identity (in progress)". |
| `Ai-services/README.md` | Config block: `AI_TENANT_ID` as optional fallback, added `CASHIER_GRPC_SERVICE_KEY` and `AI_INTERNAL_API_KEY`. New section 2.4a (in progress). Telegram section notes contact sharing (in progress). |
| `reports/TASK-059-D.md` | This report. |

## Deviations and flags for the orchestrator

1. **Ai-services port: `8001`, not `8000`.** The task and contract example say `http://ai-services:8000`, but `Ai-services/Dockerfile` runs `uvicorn ... --port 8001` and `EXPOSE 8001`, and compose publishes `8001`. A default of `:8000` would never connect. I used `http://ai-services:8001` in compose and in the `.env.example` comment. If the orchestrator wants 8000, the Dockerfile and uvicorn command must change first (outside this task). Also note `docs/contracts/member-sync.md` section 4 still shows `:8000` as an example; that file is not mine to edit.
2. **Compose now requires `AI_INTERNAL_API_KEY` even without the `ai` profile** (same as the gRPC key, because the backend service is always started). Anyone running `docker compose up` with an old `.env` will get the `:?` error until they add the key. This is intended per the task, but it is a breaking change for existing local `.env` files and is mentioned in the README quick start.
3. **Not edited (out of Section D scope):** `Ai-services/GEMINI.md`, `Ai-services/docs/*`, `docs/contracts/*`, and `.ai/*` still mention `CASHIER_GRPC_ALLOWED_TENANT_IDS` or describe the old model. A grep over the seven reserved files returns no matches for `ALLOWED_TENANT`.
4. **Ai-services README `0001_channels` revision note** (section 3.3 and the table in section 1) is now stale once migration 0002 lands. Not changed; belongs to Worker C or the orchestrator's final pass.

## Acceptance check

- `AI_SERVICE_BASE_URL` and `AI_INTERNAL_API_KEY` added with comments to both `.env.example` files: done.
- Compose wiring for backend (`AI_SERVICE_BASE_URL` default, `AI_INTERNAL_API_KEY` with `:?`) and ai-services (`AI_INTERNAL_API_KEY` with `:?`): done. Verified by parsing the YAML.
- `CASHIER_GRPC_ALLOWED_TENANT_IDS` removed from the reserved files: verified with grep, no hits.
- Registration phone requirement, staff push on owner profile completion and cashier invitation claim, Telegram contact identification, and `AI_TENANT_ID` as development fallback documented, all marked "in progress": done.
- Placeholders only, no real secrets: done.

## Commands run

- `grep -rln` over repo for env names (excluding `.env` files, `.git`, `target`, `.venv`, `.ai`, `tasks`, `reports`) to find mentions.
- `python -c "import yaml; ..."` on `docker-compose.yml` (parse only; output showed both service environment maps as expected).
- `grep -n "ALLOWED_TENANT" <reserved files>` returned no matches (exit 1).

## Remaining work

- Re-check the wording of the in-progress sections once Sections A, C, E and F are verified. Remove "in progress" markers only after verification.
- Decide the Ai-services port (8001 vs 8000) as described in deviation 1.
