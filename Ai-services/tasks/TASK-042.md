# TASK-042 — Uvicorn only; delete Telegram/AI architecture

User explicitly requests deleting all Telegram/AI agent and other application configuration and wants to design the architecture themselves. Only Uvicorn stays. This supersedes TASK-038/040/041 and PLAN as active architecture; Java UNIT pause remains. Canonical memory25 baseline.

## Exact worker reservations

- Delete entire app/, generated/, proto/, tests/ (including __pycache__ files) inside Ai-services only. No replacement application package.
- Delete .env and .env.example without reading/printing/backing up secrets. Explicit user-authorized configuration deletion.
- Delete PLAN.md (superseded architecture plan).
- Replace main.py with a single minimal plain-ASGI callable and Uvicorn main guard.
- Edit pyproject.toml: retain existing name/version/Python>=3.12/readme, generic description, only uvicorn>=0.54.0 runtime dependency (no standard extra), tool.uv package=false. Remove FastAPI, all AI/Telegram/Spring/queue/auth/DB dependencies, optional extras, dev groups and unused pytest/Ruff configs. No uv.lock edit by worker; orchestrator owns uv lock/sync after source review.
- Edit README.md into concise Indonesian Uvicorn-only startup and root URL guidance. No tokens/env/AI/bot setup.
- Edit .vscode/launch.json to only Uvicorn config running ${workspaceFolder}/main.py with uv .venv, cwd, console. No envFile or Telegram config.
- Edit .vscode/settings.json and extensions.json only to remove Ruff-specific formatter/path/recommendation since no Ruff dependency remains; preserve other Python/JSON/indent preferences. Add debugpy recommendation if useful.
- Write unique reports/TASK-042.md with baseline/reservations and meaningful checkpoints.

No other deletes/writes. Preserve .gitignore/.python-version/.venv/uv.lock and all reports/tasks/history. No parent source/Java/SQL/.ai edits. No nested agents. No tests/build/startup/import execution/dependency resolution/network/provider/Telegram/DB calls. No real .env reads even though deletion authorized.

## Architecture brief and acceptance

Only root main.py is live app code; user owns future architecture. Implement async app(scope, receive, send) using standard library and Uvicorn, no FastAPI dependency. Minimal GET / response {"status":"ok"} as JSON; other paths 404, unsupported methods405 if reasonable. Handle ASGI lifespan correctly with tiny startup/shutdown receive loop or intentionally disable lifespan in server. Reject unsupported websocket scope cleanly if needed. Keep source concise, no classes/config abstractions/startup state/background tasks. Main guard uses uvicorn.run('main:app',host='127.0.0.1',port=8001), one process, no reload; uv run main.py and uv run uvicorn main:app --host 127.0.0.1 --port 8001 supported. Source code should not depend on cwd imports from removed app.

Before any recursive removal, resolve each exact absolute target and verify it is strictly within E:/Java/cashier-saas/Ai-services. Use native PowerShell Remove-Item -LiteralPath in one shell; no cmd/rm cross-shell and no generated shell command strings. Write report checkpoint before deleting. Baseline hashes recorded under reports/artifacts/UVICORN-ONLY-20261012/baseline.json; no secret backup copies. Deletion is explicitly authorized; no extra confirmation needed.

Static AST/JSON/TOML parsing only permitted, not application imports or server requests. Report exact removed/changed files, parser results, no runtime/testing claim, uv.lock/sync pending orchestrator. Final status source ready for environment reconciliation. Do not retain AI code in new modules or add alternative bot demo entrypoints. No architecture migration scaffolding.

## Source-review correction within same reservation

Make every response body valid JSON because Content-Type is application/json (current404/405 bodies are plain text). Add Allow: GET for405. Remove incorrect receive: object / send: object annotations; use the simple unannotated callable parameters rather than introducing typing boilerplate. Preserve the small ASGI implementation and do not run app/tests. Update report checkpoint after correcting and statically parsing source.
