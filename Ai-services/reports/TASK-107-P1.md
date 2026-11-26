# TASK-107 P1 report

Status: DONE (pending orchestrator rerun)
Baseline: ded6050

## Root cause (all 10 failures, same cause)
`_summary_for` (app/api/internal_daily_summaries.py) calls `get_leakage_report(tenant_id, user_id, date_from, date_to)` and `get_menu_margins(tenant_id, user_id)` since TASK-098. The test `FakeGrpc` lacked both, so AttributeError was caught by the per-recipient `except Exception` and every recipient counted as skipped. Stale fake, not a production bug.
- The 9 delivery/sender tests (delivers_to_each_owner, every_chunk, failed_delivery, llm_failure, one_failing_recipient, pending_forever, pending_retried, recipient_without_outlet, uses_only_requested_outlet) expect delivered >= 1 and failed on the skip.
- `test_calls_match_the_real_client_signatures`: autospec of the real client returned a non-awaited-result mock for the two new methods (not None), so the summary build failed and the recipient was skipped.

## Changed files
- Ai-services/tests/unit/test_internal_daily_summaries.py: FakeGrpc gets `leakage`/`margins` dicts and `get_leakage_report` / `get_menu_margins` with real keyword-only signatures (None by default); autospec test sets both to None and asserts both awaited; ONE new test `test_leakage_and_margin_sections_use_only_the_requested_outlet` (no test in tests/unit covered the leakage/margin sections of build_summary).

## Deviations / open questions
None. Production code untouched.

Test status: NOT RUN (policy)
