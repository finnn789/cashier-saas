# TASK-084-P1 report

Status: DONE (offline, not executed). Baseline: 20faa7a + dirty tree of TASK-084 wave 1.

## Changed files
- `app/integrations/grpc/generated/cashier_service_pb2.py`, `cashier_service_pb2_grpc.py`: regenerated from `../src/main/proto/cashier_service.proto` (`protoc -I../src/main/proto`). The hand-patched relative-import try/except in `_pb2_grpc.py` was restored after generation.
- `app/integrations/grpc/client.py`: `get_leakage_report(*, tenant_id, user_id, date_from="", date_to="")` via `_call_insight("GetLeakageReport")` (None on any error).
- `app/tools/insights.py`: `LeakageReportTool` (`leakage_report`), optional `start_date`/`end_date`, validates ISO dates and range 0..91 days client-side (invalid_input in Indonesian), non-owner/error -> `UNAVAILABLE`, no outlets -> "Belum ada stok opname pada periode ini.", else a compact text block per outlet (header with opname count, total rugi, flagged count; one bullet per flagged line). All numbers are the backend decimal strings verbatim; empty string -> "-".
- `app/tools/__init__.py`: registration + export (registration lives here, `registry.py` is only the class and was NOT changed).
- `app/ai/prompts.py`: tool name added to the one analytics hint line.

## Deviations
- `app/tools/__init__.py` is not in the reserved list; it is the only place tools are registered, so it was edited (3 small edits).
- Tool params are `start_date`/`end_date` (consistent with `sales_summary`, and `from` is a Python keyword); the client method uses `date_from`/`date_to` as specified.
- `Ai-services/proto/cashier_service.proto` (tracked copy, README says it must be identical) is NOT updated (not reserved). Stubs were generated straight from `../src/main/proto`. Orchestrator should copy the file.

## Tests that need updating
- `tests/unit/test_tools.py` (expected tool names list, line ~24) and `tests/unit/test_grpc_tools.py` (~218, tool names list) now see a 4th tool `leakage_report`.
- `tests/unit/test_insights.py`: add `get_leakage_report` to `CASES`; add a formatting test for `LeakageReportTool` (empty, null money -> "-", invalid/over-92-day range, non-owner -> UNAVAILABLE). Check any prompt-length/hint-text assertion in prompt tests.
- README section 2.4 (tool list) not updated (not reserved).

## Open questions
- None.

Test status: NOT RUN (policy).
