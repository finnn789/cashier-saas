# TASK-034 review corrections

Initial submission has incorrect fixtures and fluent mocks. Same TASK-034 exact allowlist/baseline8; memory9 coordination only. No tests/Maven/production/canonical changes. Persist correction report.

1. Exactly366days test uses 2024-01-01 through 2025-01-01, which is367 inclusive days (2024 leap year). Correct end to2024-12-31, expected exclusive end2025-01-01. Do not change production date limit.
2. Repository deep-stub chain returns different fluent builder instances. Current root query verification does not observe actual chain, and createdAt where called twice. Replace with controlled explicit typed fluent mocks that exercise real repository and return correct chain types. Verify actual eq(tenantId), eq(outletId), ge(start), lt(end), optional values, projection actor.fullName, order by createdAt then id, offset and size+1, count predicate parity. Merely verifying predicate names cannot detect wrong tenant/outlet IDs. RETURNS_SELF plus explicit restriction/select mocks is one option; inspect cached Blaze javap API. Avoid real EntityManager/database.
3. Cover page>1million, size0, entityType>80, exact bounds as appropriate. Current no-overflow method name claims trimming yet has3 rows/size20; rename accurately. Improve validation independence with parameterized cases where useful.
4. Use tools.jackson.databind Jackson3 everywhere; check mapper null-free nested response serialization. Do not weaken authorization/date assertions to force success. Final READY FOR TEST EXECUTION with test status unrun.
