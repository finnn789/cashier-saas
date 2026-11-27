# TASK-115-E1: chat throughput measurement (scout, read-only)

Source: `Ai-services/logs/ai-services.log`, 807 lines, 2026-11-07T13:52Z to 2026-11-10T11:46Z. No rotated files.

- `update_handled`: 17 events. elapsed_ms p50 7168, p95 14033, max 19293.
- `ai_graph_invoke_finished`: 9 events. elapsed_ms p50 6903, p95 12027, max 12254.
- Busiest minute 2026-11-08T11:47: 3 received, 4 handled, sum elapsed 20.5 s.
- Distinct tenants: 2.
- Also seen: 3 `grpc_call_failed`, 1 `sender_transport_failure`, 1 `deliver_abandoned`.

Conclusion: too small to decide. 17 updates in 3 days, dev traffic only. `update_received` has no field separating lock wait from processing. F1 stays BLOCKED; re-measure on real traffic (hundreds of updates), ideally after adding a lock-wait duration to the `chat_handle_started` log.
