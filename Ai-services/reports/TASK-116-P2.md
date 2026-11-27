# TASK-116 P2 report

Status: code complete, depends on P1 (`app/analysis/product.py` was not yet present when written).

Changed files
- `Ai-services/proto/cashier_service.proto`: rpc GetProductPerformance, 3 new messages, `ProductTotal.product_id = 7` (contract verbatim).
- `Ai-services/app/integrations/grpc/generated/cashier_service_pb2.py`, `cashier_service_pb2_grpc.py`: regenerated with the README protoc command.
- `Ai-services/app/integrations/grpc/client.py`: `get_product_performance`.
- `Ai-services/app/tools/product_analysis.py` (new): `ProductAnalysisTool`.
- `Ai-services/app/tools/__init__.py`: register + export.
- `Ai-services/app/ai/prompts.py`: `product_analysis` added to owner-only analytics list.

Deviations / extras
- `GEMINI.md` and `AGENTS` path: `Ai-services/GEMINI.md` does not exist; only AGENTS.md read.
- Extra output beyond packet: ranking shows top 5 by quantity, top 5 by sales, bottom 5 by quantity; no >366-day check in the tool (Java returns INVALID_ARGUMENT -> UNAVAILABLE).
- Imports used from P1: `app.analysis.money` (`format_number, format_rupiah, to_decimal`), `app.analysis.product` (`abc_classes, growth_pct, menu_matrix, rank`).

Tests that look wrong (not edited)
- `test_product_analysis.ProductAnalysisToolTests.test_abc_text_has_rupiah_sales_and_class_letters`: sales 500k/400k/100k; D1 gives Roti cumulative-before 90% -> B (<95%), but the test expects `\bC\b` on the Roti line. Either the test data should make Roti's prior cumulative >= 95% (e.g. 600k/350k/50k) or the expectation should be B. Tool prints whatever `abc_classes` returns.

Test status: NOT RUN (orchestrator runs GREEN). Stub import smoke-checked only.
