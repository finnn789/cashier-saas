# TASK-080 T4 report (docs and contracts)

Status: done. Docs only; nothing compiled or run. JSON files were validated with a one-off script (`json.loads`) at write time.

## Files
- `docs/contracts/cashier-service.grpc.schema.json`: new `$defs` (`decimal`, `ownerUserId`, the 3 requests/responses and nested messages), 3 `x-methods` entries (`x-ownerOnly`, `x-readOnly`), 3 examples; description notes the owner-only RPCs and non-fixed decimal scale. Existing content unchanged (additions only).
- `docs/contracts/cashier-service.grpc.test-messages.json`: placeholders `<OWNER_USER_ID>`, `<CASHIER_USER_ID>`; 3 new case lists (owner OK, cashier/outsider/unknown tenant/other tenant all PERMISSION_DENIED, missing user/blank tenant INVALID_ARGUMENT, date range cases for GetSalesSummary, date cases for GetAnomalies).
- NEW `docs/contracts/daily-summary.md`: endpoint, 200/401/422/503, recipients, message content, Java sender (dedupe, triggers, retries incl. A1 cutoff, timeouts, disabled conditions), property/env table, curl example.
- `Ai-services/README.md` (3 new tools, owner-only, new section 2.5 endpoint), `docs/architecture.md` (M7 subsection with sequence diagram under section 6; "two lookup RPCs" sentence adjusted), `README.md` (M7 feature line, gRPC line).

## Notes for the orchestrator
- Validation order documented as implemented in `CashierInsightGrpcHandler`: tenant + owner check first (PERMISSION_DENIED), then date validation, so test cases for date errors use an owner.
- Messages quoted in test cases (`date range is invalid`, `<field> must not be blank`, `<field> must be an ISO date (yyyy-MM-dd)`) come from the handler source, not from a run.
- Money/quantities in the new RPCs are plain decimal strings without fixed scale (mapper uses `toPlainString`), unlike the older 2-decimal `money` def; documented via `$defs/decimal`.
- A 200 response with `delivered = 0` still marks the dispatch SENT on the Java side (any 2xx); documented as best-effort.
- Test-messages file has a blank line before the new `GetSalesSummary` block (cosmetic).
- Not verified: behaviour at runtime, `README.md` line endings (file rewritten with LF; git warns about CRLF normalisation as for other files).
