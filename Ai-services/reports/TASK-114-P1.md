# TASK-114 P1 (python-worker, GREEN)

Status: done. Test status: NOT RUN (orchestrator runs GREEN).
Baseline: 545ac38.

## Changed files
- `Ai-services/app/integrations/grpc/client.py`: `consume_ai_question` now has its own try/except (same shape as `_call_insight`, `_log_rpc_error` kept). PERMISSION_DENIED -> `{'allowed': False, 'reason': 'OWNER_ONLY'}`; every other error -> `None`.
- `Ai-services/app/chat/service.py`: `_QUOTA_REFUSALS['OWNER_ONLY']`, new `NOT_VERIFIED_MESSAGE`; in `handle`, `java_user_id is None` -> `_finish(started, 'not_verified', NOT_VERIFIED_MESSAGE)` before consume (user message already stored); consume now passes `str(java_user_id)`.

## Contract points
D1, D2, D3 done. `_call_insight` untouched (outside reserved paths), so the consume RPC duplicates its try/except body rather than reusing it.

## Deviations
None.

## Tests that may need updating
None beyond the T1 changes.

## Open questions
None.
