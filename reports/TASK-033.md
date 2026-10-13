# TASK-033 — business settings unit tests

## Checkpoint 1 — reservation and plan

- Memory acknowledged: `.ai/MEMORY.md` version 8.
- Baseline acknowledged: `reports/artifacts/UNIT-20261007/baseline*` SHA-256 values were inspected before edits (`C79AA3323483D88CA56BAC95F64AD3499E10DF6A2062AFF5A5D30B0BF07E826D`, `D8C4A929B31437AD640D406E82D458441A6557CE70CA591D4A232693996FE302`). The working tree is intentionally dirty and will be preserved.
- Reserved writes only: `src/test/java/org/lite/project/cashiersaas/unit/service/MerchantBusinessSettingsServiceTest.java`, `src/test/java/org/lite/project/cashiersaas/unit/dto/MerchantBusinessSettingsMapperTest.java`, and this report.
- Scope: Mockito/JUnit 5 `@Tag("unit")` service and mapper tests for owner guard/scoping, mutation/audit behavior, currency-history conflict, normalization/validation, secure audit details, and Jackson null omission.
- No Maven/test/build, database, network, production, canonical-state, or other-file writes will be performed.

## Checkpoint 2 — implementation and static review

- Added `MerchantBusinessSettingsServiceTest` with JUnit 5/Mockito `@Tag("unit")` coverage for scoped owner reads, guard-failure non-mutation, normalized tenant/outlet updates, audit actor/outlet/before-after values, currency history conflict, same-currency history bypass, scale-only no-op, validation boundaries, optional field clearing, and safe/parseable audit JSON.
- Added `MerchantBusinessSettingsMapperTest` with explicit tenant/outlet mapping and Jackson null-omission coverage.
- Initial static review used `git diff --check` for tracked changes. That command does not cover the newly untracked test files. The initial claim that no meaningful order fixture existed is superseded by the correction handoff below.

## Final handoff

Initial handoff, superseded by the corrected handoff below.

- Changed files:
  - `src/test/java/org/lite/project/cashiersaas/unit/service/MerchantBusinessSettingsServiceTest.java`
  - `src/test/java/org/lite/project/cashiersaas/unit/dto/MerchantBusinessSettingsMapperTest.java`
  - `reports/TASK-033.md`
- Test status: **NOT RUN** — task policy explicitly prohibits Maven/tests/build; no database, server, or network operation occurred.
- Initial submission was not accepted; production correctness remains unverified because tests have not run.
- **READY FOR TEST EXECUTION.** Recommended serialized command for the orchestrator: run the two new unit classes (or the unit-tagged suite) after all workers submit.

## Correction checkpoint 1

- Read `tasks/TASK-033-REVIEW.md`; baseline8 and the original three-file reservation remain in effect.
- Plan: switch both files to Jackson3, derive the no-op request directly from its populated entities, exercise exact control-character roundtrips, and add independent validation/boundary, currency-success, owner-read-denial, and complete mapper cases.
- No execution is authorized in this worker; test/build/DB status remains NOT RUN.

## Correction checkpoint 2

- Both tests now import `tools.jackson.databind` Jackson3. Cached Jackson3 3.1.5 source confirms the constructor, readTree/writeValueAsString, and JsonNode APIs used. `javap` was unavailable, so API verification used read-only ZIP source inspection instead.
- No-op now copies every populated field from its actual tenant/outlet fixture before changing only rate scale. Audit roundtrip now includes U+0000, backspace, formfeed, newline, tab, quotes, and backslash in both before and after names, verifies exact decoded strings, and rejects raw control characters.
- Added owner read denial, currency change without history plus before/after audit, Action assertions, 22 independent invalid-value cases, inclusive 0/100 rate cases, complete populated mapper coverage, and independent null omission.
- Test/build/DB status: NOT RUN.

## Corrected final handoff

- Addressed all four review findings within the original reservation. Changed only the two TASK-033 test files and this report.
- Jackson3 usage was checked against cached source; no dependency changes were made. Static inspection confirms neither reserved test imports Jackson2 databind.
- Service scenarios: scoped get and denied get; denied update with no settings/audit writes; normalized business/outlet/phone/logo/timezone/rate update with correct tenant, actor, outlet, entity ID, Action and before/after audit; currency history conflict with pending name/rate changes remaining unapplied; currency success without history; same-currency lookup bypass and a truly identical scale-only no-op; inclusive 0 and 100 for both rates; existing POS order rate snapshots, tax/service amounts and total preserved when future rates change; blank optional fields cleared; exact before/after control-character JSON roundtrip and credential redaction.
- Independent invalid cases (22): unknown ISO, zero-/three-fraction currencies, invalid timezone; null/negative/over100/excess-scale tax and service rates; HTTP/credential/port0/port70000 logo; null/blank/overlength business and outlet names.
- Mapper scenarios: every nonnull field mapped from its proper tenant/outlet (with different entity timezones and addresses), and null optionals independently absent from parsed Jackson3 JSON.
- Expected invocations: 33 service cases (9 ordinary + 22 invalid + 2 bounds) and 2 mapper cases. This is a static inventory, not an execution result.
- **NOT RUN:** no Maven, unit tests, build, DB, startup, or nested agents. Compile/runtime results and production correctness remain unverified; no assertion was weakened to accommodate a discovered production failure.
- **READY FOR TEST EXECUTION** by the orchestrator's serialized test run. Baseline8/reservation unchanged; existing dirty work preserved.
