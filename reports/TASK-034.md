# TASK-034 — owner audit viewer unit tests

## Initial checkpoint — 2026-10-07

- Acknowledged canonical memory baseline: **Memory version 8**.
- Reserved write paths acknowledged: the three TASK-034 unit-test files and this report only.
- Dirty working tree was observed before any write and will be preserved; no production, canonical, migration, POM, database, network, build, Maven, or Git actions will be performed.
- Baseline source snapshots are reserved at `reports/artifacts/UNIT-20261007/baseline*`: `baseline-git-status.txt` SHA-256 `C79AA3323483D88CA56BAC95F64AD3499E10DF6A2062AFF5A5D30B0BF07E826D`; `baseline-sha256.csv` SHA-256 `D8C4A929B31437AD640D406E82D458441A6557CE70CA591D4A232693996FE302`.
- Acceptance target: Mockito/JUnit 5 `@Tag("unit")` coverage for owner audit guard, scoped filters/pagination/date boundaries, mapper/null-free serialization, and mock-level repository query construction only. No claim of database isolation will be made from mocks.

## Test status

Not run by task instruction. The orchestrator will execute tests serially after submissions.

## Review correction checkpoint

Acknowledged `tasks/TASK-034-REVIEW.md`: baseline8 and the original four reserved paths remain controlling; memory9 is coordination only. The initial submission contained an incorrect inclusive leap-year fixture, unsuitable deep-stub verification, and Jackson2 imports. Correcting these and adding independent rejected/accepted boundary cases. Earlier readiness claims are superseded by this correction review. No tests/build/Maven/database execution is authorized for this worker.

## Implementation checkpoint — test sources written

- `src/test/java/org/lite/project/cashiersaas/unit/service/MerchantAuditServiceTest.java`: owner guard denial with no repository access; tenant/outlet/filter forwarding; default and explicit pagination; overflow trimming, `hasNext`, and total; fixed Asia/Jakarta and New York DST half-open date instants; reversed, oversized, extreme date, invalid zone, page/size and text-bound failures; exact 366-day acceptance.
- `src/test/java/org/lite/project/cashiersaas/unit/dto/MerchantAuditMapperTest.java`: projection mapping including actor full name, reason and details, plus Jackson null omission assertions.
- `src/test/java/org/lite/project/cashiersaas/unit/repository/MerchantAuditRepositoryTest.java`: Mockito Blaze query-shape checks for mandatory tenant/outlet/window predicates, optional filters, stable ordering, pagination and scoped count. These mocks do **not** prove database-level tenant isolation; they verify only construction intent.
- No production file, canonical state, POM, migration, or pre-existing test has been modified.

## Final checkpoint — READY FOR TEST EXECUTION

Changed files, exactly within the reservation:

- `src/test/java/org/lite/project/cashiersaas/unit/service/MerchantAuditServiceTest.java`
- `src/test/java/org/lite/project/cashiersaas/unit/dto/MerchantAuditMapperTest.java`
- `src/test/java/org/lite/project/cashiersaas/unit/repository/MerchantAuditRepositoryTest.java`
- `reports/TASK-034.md`

Static review completed: source imports, JUnit 5 `@Tag("unit")` placement, Mockito interaction paths, fixed instant expectations, JSON null-omission assertions, and the reserved changed-file set were inspected. No production-source defect was identified by this static-only worker review. Repository mocks establish query-construction intent only; they do not establish database isolation or SQL semantics.

Tests/build/Maven/database were **not run**, per TASK-034. Runtime compilation and test results remain unverified and must be produced by the orchestrator's serial execution. No source changes outside the reserved paths were made by this worker.

## Corrected submission — READY FOR TEST EXECUTION

All four review findings addressed within the original exact allowlist:

- Service test: accepted interval now uses 2024-01-01 through 2024-12-31 (366 inclusive days), with exclusive end 2025-01-01 verified for both rows and count. The old 367-day fixture is now an explicit rejection case. DST row/count bounds remain asserted.
- Repository test: removed deep stubs and test-generated verification chains. Typed RETURNS_SELF criteria/projection mocks and explicit restriction/select/count mocks exercise the real repository methods. Verifies actual tenant/outlet UUID equality, inclusive start/exclusive end instants, action/actor/entity values for both page and count, two createdAt restrictions per query, actor.fullName projection, projection field order, createdAt/id descending order, offset, size+1, returned rows and total. Optional-filter absence still verifies concrete outlet equality; mock checks do not prove database isolation or SQL null semantics.
- Bounds: nine independently parameterized rejection cases cover reversed dates, 367 inclusive days, LocalDate.MAX overflow, negative/above-maximum page, zero/above-maximum size, and action/entityType lengths 81. Accepted boundary cases exercise pages 0/1,000,000, sizes 1/100 and both text lengths 80. The default-pagination test name accurately describes its no-overflow behavior.
- Mapper: Jackson3 tools.jackson.databind imports replace Jackson2; verifies all populated projection fields and null omission for standalone entries and nested page entries, while retaining primitive page metadata.

Exact changed paths:

- src/test/java/org/lite/project/cashiersaas/unit/service/MerchantAuditServiceTest.java
- src/test/java/org/lite/project/cashiersaas/unit/dto/MerchantAuditMapperTest.java
- src/test/java/org/lite/project/cashiersaas/unit/repository/MerchantAuditRepositoryTest.java
- reports/TASK-034.md

Static evidence: inspected corrected sources and cached Blaze API signatures with javap (inspection only). Baseline CSV SHA-256 comparison matched audit service/repository/mapper/controller/filter/response production sources, MerchantAccessGuard, its existing test, and POM. Latest observed canonical memory is version11; original baseline8 reservation is unchanged and canonical state was not edited.

Honest status: no tests, Maven, compilation, build, database, or Git commands ran during this correction turn. Compilation and test outcomes remain **unverified**. No production defect was established by this static review. The orchestrator must execute the corrected tests serially.
