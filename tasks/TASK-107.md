# TASK-107: Fix the 10 failing Python unit tests (daily summary endpoint)

Status: DELEGATED. Memory version: 102. Branch `feature/leakage-report`. Baseline commit: `ded6050` (dirty: none).

## User instruction (2026-11-25)
"delegasi ke Python worker, untuk Audit Unit Test yang gagal dan setelah itu silahkan Perbaiki."

## Global rules (every worker)
- Report: `Ai-services/reports/TASK-107-P1.md`.
- Unit test writing rule (AGENTS.md): no new helpers/base classes/fakes files; extend the existing fake; production code is not changed to make it testable.

## Verified facts (orchestrator ran the suite 2026-11-25)
- Command: `Ai-services/.venv/Scripts/python.exe -m unittest discover -s tests/unit` -> Ran 269, FAILED (failures=10). The orchestrator runs it; the worker does NOT.
- All 10 failures are in `tests/unit/test_internal_daily_summaries.py`, class `DailySummaryEndpointTests`: `test_calls_match_the_real_client_signatures`, `test_delivers_to_each_owner_and_counts`, `test_every_chunk_must_be_sent`, `test_failed_delivery_is_skipped_without_retry`, `test_llm_failure_sends_the_summary_without_the_note`, `test_one_failing_recipient_does_not_stop_the_others`, `test_pending_forever_gives_up_after_three_attempts`, `test_pending_is_retried_then_delivered`, `test_recipient_without_the_outlet_or_without_data_is_skipped`, `test_uses_only_the_requested_outlet_section`. Symptom: every recipient counted as `skipped` (`delivered` 0).
- Root cause (likely): TASK-098 made `app/api/internal_daily_summaries.py::_summary_for` (:76) also call `client.get_leakage_report(...)` (:95) and `client.get_menu_margins(tenant_id=..., user_id=...)` (:101). The test `FakeGrpc` (:53) only has `get_sales_summary`, `get_restock_suggestions`, `get_anomalies`, so the call raises and the per-recipient `except Exception` (:146) skips the recipient. `test_calls_match_the_real_client_signatures` uses autospec of the real client, so the real `CashierGrpcClient` (`app/integrations/grpc/client.py`) signatures are the contract.
- Contract from TASK-098: a `None` gRPC result for leakage or margins omits that section of the summary.

## P1 — fix the daily summary tests (`python-worker`)
Reserved:
- `Ai-services/tests/unit/test_internal_daily_summaries.py`
- `Ai-services/reports/TASK-107-P1.md`
Do:
1. Confirm the root cause by reading `_summary_for`, the real `CashierGrpcClient.get_leakage_report` / `get_menu_margins` signatures, and the test fakes/autospec setup.
2. Add `get_leakage_report` and `get_menu_margins` to the existing `FakeGrpc` with the real signatures (keyword names identical), returning `None` by default (section omitted) so existing expected texts stay valid. Make the autospec test also cover the two new methods.
3. If, and only if, reading shows a real production bug (not a stale fake), do NOT edit production code: describe it in the report.
4. Do not add new test cases unless a behaviour of TASK-098 (leakage/margin sections present when data exists) has no test anywhere in `tests/unit`; if missing, add ONE test in this file using the same fake.
Acceptance:
- Diff only in the reserved test file; report lists the root cause per failing test, test status "NOT RUN (policy)". The orchestrator reruns the suite.
