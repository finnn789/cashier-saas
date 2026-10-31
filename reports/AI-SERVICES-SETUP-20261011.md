# AI Services setup checkpoint

Tanggal: 2026-10-11 (Asia/Jakarta). Baseline memory15 -> reviewed checkpoint16.

## User scope

Create all agreed directories at `E:\Java\cashier-saas\Ai-services`, initialize with uv, prepare important configuration in .env.example, plan first, persist memory. Delegation/application implementation only when user requests next session. Main session performed infrastructure/documentation only; no worker spawned/executed during this setup.

## Delivered and inspected

- `uv init --bare --no-package --name ai-customer-service --python 3.12 --no-workspace --vcs none` created standalone metadata without nested Git repository or application template.
- Directories: app/chat, app/whatsapp, app/ai, app/cases/faq, app/cases/order_status, app/cases/support_ticket, app/spring, app/messaging, proto, generated, tests, reports. Empty __init__.py markers preserve Python packages; nonpackage directories have .gitkeep. No main.py/endpoints/tools/worker source/proto/test implementation.
- Python pin `.python-version`: 3.12. Actual installed CPython3.12.13. uv0.11.14 installed before request.
- Runtime dependencies installed via uv: FastAPI, uvicorn[standard], Pydantic Settings, HTTPX, LangChain, LangGraph, langchain-ollama, grpcio, protobuf, PyJWT[crypto], Redis. Dev dependencies: grpcio-tools, Ruff, pytest, pytest-asyncio (installed only, tests not run). PostgreSQL checkpoints/psycopg are optional postgres extra, also installed in local environment for dependency setup; no DB connection.
- uv.lock resolved75 packages; `uv sync --locked --extra postgres` exit0, checked72 installed packages. Exact versions in lockfile; examples: FastAPI0.143.0, LangChain1.4.4, LangGraph1.2.14, grpcio1.84.0, Redis8.1.0, langchain-ollama1.1.0.
- Direct interpreter import/metadata/TOML inspection exit0: fastapi/langchain/langgraph/grpc/httpx/redis/pydantic_settings/jwt/langchain_ollama/grpc_tools imported successfully. This verifies environment only, not application behavior.
- `.env.example` contains36 config keys for application/WebSocket, model, JWT, Spring gRPC/TLS/deadline, Meta webhook/send, Redis queue/dedup and optional checkpoint DSN. Secrets, model name and Meta version blank. Provider Ollama is reversible initial assumption. JWKS is an unimplemented auth option, not current Spring HMAC config.
- Local .gitignore confirmed by git check-ignore for .venv/pyvenv.cfg, .env and *.key. `.env.example` visible in git untracked listing; environment/cache/secrets excluded.
- PLAN.md maps future files to responsibilities, acceptance and staged worker scopes; README documents uv usage and explicit lack of runnable app. Prior Java paused UNIT scope remains paused.

## Limitations and follow-up

No app/API/Maven/unit/integration tests, app startup, model download/inference, Meta request, Redis/DB connection, Java source/pom/schema edits or worker delegation. Runtime connectivity, public behavior and security are pending implementation and user-authorized testing.

Next session: read .ai/MEMORY.md + .ai/TASKS.md + Ai-services/PLAN.md. On explicit delegation instruction, capture current dirty baseline/reservations and review customer/channel-to-tenant authentication before creating worker packets. Existing Cashier JWT represents staff/account scopes; never treat it as WhatsApp customer identity. Spring gRPC handler and support ticket persistence are not already implemented. Workflow version/exact Java/proto/Python contracts need source review first.

TASK-037 is a historical abandoned embedded Spring practice packet from earlier conversation. User interrupted worker escalation then chose chat-only examples, later chose Python AI-services setup. No reports/TASK-037.md or example implementation found at setup; do not resume that packet. No claims about an unverified background execution are made.

## Execution notes

Initial sandbox `uv python find 3.12` hit denied cache writes outside workspace; rerun with required escalation succeeded. uv init/add/sync ran through approved escalation for cache/network/environment setup. Cross-drive hardlinks fell back to copies; installation completed. Ruflo memory_search and hooks_route called for coordination context; no matching AI plan was authoritative and user deferred spawning. PRD/.ai sources remain canonical. `.ai/DECISIONS.md` and workflow/template references were absent; preserved existing guidance rather than inventing past decisions.
