# TASK-096 J1 report

Status: DONE (pending orchestrator review)
Baseline: cee0f5f (working tree feature/leakage-report)

Changed (modified):
- src/main/java/org/lite/project/cashiersaas/dto/response/FnbStaffOrderItemResponse.java: added `UUID productId` after `itemId`.
- src/main/java/org/lite/project/cashiersaas/dto/FnbStaffOrderMapper.java: `item(...)` sets productId with null guard (same style as options); getId on a proxy does not load it.

Deviations: none.
Existing tests needing update: none expected (additive field); strict JSON-shape assertions, if any, not checked.
Open questions: none.
Tests: NOT RUN (policy)
