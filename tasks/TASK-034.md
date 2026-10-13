# TASK-034: owner audit viewer unit tests

Status: SOURCE REVIEW ACCEPTED / EXECUTION PAUSED BY USER (memory13). Memory baseline8. User explicitly authorized testing/delegation on 2026-10-07. Actual worker: authenticated Codex CLI.

Read AGENTS.md, .ai/MEMORY.md, active PRD header, tasks/TASK-031.md, docs/merchant-audit.md and current source. Role guidance .codex/agents/vertical_slice_builder.toml; latest user delegation wins. Missing historical/canonical referenced files must not be restored. Baseline copies/hashes reports/artifacts/UNIT-20261007/baseline*. Preserve dirty work.

Write only:
- src/test/java/org/lite/project/cashiersaas/unit/service/MerchantAuditServiceTest.java
- src/test/java/org/lite/project/cashiersaas/unit/dto/MerchantAuditMapperTest.java
- src/test/java/org/lite/project/cashiersaas/unit/repository/MerchantAuditRepositoryTest.java
- reports/TASK-034.md

Create meaningful JUnit5 @Tag("unit") Mockito tests. Cover guard denial no repository access; tenant/outlet/date/action/actor/entity predicates passed identically to rows/count; default and explicit page/size, overflow row trimming/hasNext/total; outlet-local half-open instants including DST with fixed explicit dates; reversed/>366-day/extreme LocalDate.MAX dates, invalid timezone and page/size/filter bounds; exactly366days accepted. Mapper projection/actor fullName/reason/details/null-free Jackson3 serialization. Repository behavior scoped count/list/stable ordering/null-outlet exclusion using Mockito Blaze criteria where practical; avoid claiming database isolation proven by mocks. No DB/full-context/API tests or production edits. Report any source defects without hiding failures.

Persist report first acknowledging memory8/reservations/baseline, at checkpoints and final exact changed files/scenarios/test status. Do not run tests/Maven/build: orchestrator runs serially after all submissions. No production/pom/SQL/canonical/other tests/nested agents/git/DB/network/secrets. Final READY FOR TEST EXECUTION.
