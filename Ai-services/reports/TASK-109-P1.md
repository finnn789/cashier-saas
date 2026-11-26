# TASK-109 P1 (GREEN): remove the legacy chat path

## Status
DONE, pending orchestrator GREEN run. Test status: NOT RUN (orchestrator runs GREEN).

## Baseline
`ded6050`, branch `feature/leakage-report`. `app/chat/service.py` was clean before this task.

## Changed files
- `Ai-services/app/chat/service.py`
- this report

## Contract points done
- Legacy branch of `ChatService.handle` removed (direct `ai.reply`, retries, tool dispatch).
- `graph is None`: returns `_finish_error(started, 'AI_NOT_CONFIGURED', 'ai_not_configured')` before
  `consume_ai_question`. The USER message is still stored first, as before.
- `ai_path` variable, 'legacy' value and the `ai_path_selected` log line removed.
- Graph path dedented one level; `git diff -w` shows no logic change inside it.
- Removed imports `json`, `build_prompt`, and `OpenRouterClient` (the last was already unused at baseline).
- Removed the `self.tools` field (only the legacy branch read it). The `tools` constructor parameter stays
  and still goes to `build_chat_graph`.
- `ai` constructor parameter and the existing settings check kept. `build_prompt`, `ToolRegistry.dispatch`
  and `OpenRouterClient` fields not deleted.

## Deviations
None. `Ai-services/GEMINI.md`, named in the worker start steps, does not exist in the repository.

## Tests that will need updating
None found by search: no test or app code reads `service.tools` or the removed log events.
`tests/integration/test_shared_bot_tenancy.py` calls `handle` with no graph and no `ai`; that behaviour is unchanged.

## Open questions / for the orchestrator
- Now unused by `app/` code (not deleted, per packet): `build_prompt` (only `tests/unit/test_ai.py`),
  `ToolRegistry.dispatch` and `get_openrouter_schemas` (tests; also described in `app/tools/README.md:58`),
  `OpenRouterClient` result fields `tool_calls`/`raw_message`, `AIError.retryable`/`delay`.
  `OpenRouterClient.reply` is still used by `app/chat/daily_summary.py`.
- The kept `self.ai.settings` check still runs after the quota is consumed. It only matters when a graph exists
  while `ai.settings` lacks key/model; `bot.py` passes `ai_settings=settings.ai`, so confirm whether both can
  differ before moving or dropping it. Not changed.

## Remaining work
Orchestrator GREEN run of the targeted Python modules.
