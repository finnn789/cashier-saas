# TASK-085-P1 report

Status: DONE (code complete), not tested.
Baseline: commit 20faa7a + uncommitted TASK-084 tree; Java proto (with J2's GetMenuMargins block) already present in `src/main/proto/cashier_service.proto`.

## Changed files
- `Ai-services/proto/cashier_service.proto`: byte copy of the Java proto (adds `GetMenuMargins` rpc + 4 messages).
- `Ai-services/app/integrations/grpc/generated/cashier_service_pb2.py`, `cashier_service_pb2_grpc.py`: regenerated with `uv run python -m grpc_tools.protoc -Iproto ...` (rc 0).
- `Ai-services/app/integrations/grpc/client.py`: `get_menu_margins(*, tenant_id, user_id, max_margin_pct="")` via shared `_call_insight` (None on any error).
- `Ai-services/app/tools/insights.py`: `MenuMarginsTool` (`menu_margins`), optional `max_margin_pct`; client-side validation (finite Decimal 0..100, else `invalid_input`); empty user_id or gRPC None -> `UNAVAILABLE`; compact per-outlet text with `-` for unknown values; numbers only from the tool.
- `Ai-services/app/tools/__init__.py`: import, registry registration, `__all__`.
- `Ai-services/app/ai/prompts.py`: `menu_margins` added to the analytics tool-name line only.

## Contract points done
Client method, tool, Indonesian description, optional `max_margin_pct`, compact text output, regenerated stubs, proto copy.

## Deviations
None. Tool also returns "Belum ada outlet." when the response has no outlets.

## Tests that will need updating
- `tests/unit/test_tools.py:24` tool-name list: add `menu_margins` (and note `leakage_report` is also absent there; check whether the list is an exact match).
- `tests/unit/test_grpc_tools.py:~218` tool-name list: same.
- New cases (user-requested testing only): `MenuMarginsTool` formatting, invalid `max_margin_pct` ("abc", "-1", "101", "NaN"), None result -> unavailable.

## Open questions
None.

## Test status
NOT RUN (policy). Only a bare `import app.tools` / registry construction was executed as a smoke check (no network/DB).
