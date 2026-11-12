# TASK-075 W3 - Python AI service order status text

## Changed files
- `Ai-services/app/tools/order.py`: module-level `_STATUS_LABELS` (OPEN "Belum dibayar", COMPLETED "Selesai (sudah dibayar)", CANCELLED "Dibatalkan"); unknown code falls back to the raw code. Result dict keeps all existing keys/values and adds `status_label`; message now reads `berstatus {status_label} dengan status pembayaran ...`.
- `Ai-services/tests/unit/test_grpc_tools.py` (`OrderStatusToolTests`): new `test_execute_order_status_codes_mapped_to_text` (subTest per OPEN/COMPLETED/CANCELLED, asserts `status`, `status_label`, message); existing `PROCESSING` test now also asserts `status_label == "PROCESSING"` (unknown-code fallback, message text unchanged).
- `Ai-services/app/tools/README.md`: NOT changed. It does not document order statuses (only a generic example tool returning `"status": "PENDING"`).

## Grep evidence
Searched `Ai-services/` (excluding `.venv`, `.uv-cache` noise kept visible, `reports/artifacts`, `docs/progress`, `__pycache__`, `.idea`) for `NEW|CONFIRMED|PREPARING|READY|PROCESSING` as whole words.
- No hits for NEW/CONFIRMED/PREPARING/READY used as an ORDER status. Hits are unrelated: Telegram command kind `NEW` (`app/channels/telegram/handlers.py`, `app/chat/service.py`, `tests/unit/test_conversation.py`, `test_telegram_intake.py`, `test_langgraph_chat.py`), "READY FOR TESTING" in reports/GEMINI.md/docs, `.uv-cache` third-party code, a log file.
- `PROCESSING` (not in the requested list, fake legacy order status used as test fixture data; works through the unknown-code fallback, not changed): `tests/unit/test_grpc_client_security.py:55`, `tests/unit/test_langgraph_chat.py:73,289,305,329`, `tests/unit/test_tenant_scope.py:237`, `tests/unit/test_grpc_tools.py:182` (this one touched as above). `docs/telegram-conversation-design.md:192` PROCESSING is an inbox state, unrelated.

## Tests
Not run by policy (no pytest or any code execution).
