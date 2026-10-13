# TASK-033: business settings unit tests

Status: SOURCE REVIEW ACCEPTED / EXECUTION PAUSED BY USER (memory13). Memory baseline8. User explicitly authorized unit-test creation/delegation after feature handoff on 2026-10-07. Actual worker: authenticated Codex CLI; Ruflo coordinates only.

Read AGENTS.md, .ai/MEMORY.md, PRD-MVP.md active header, tasks/TASK-030.md, docs/business-settings.md and current source. Use .codex/agents/vertical_slice_builder.toml as role guidance; explicit user delegation overrides historical no-agent skill text. PROJECT_MEMORY, .ai/DECISIONS.md and .ai/context/workflow.md are missing; do not fabricate or restore them. Baseline copies/hashes: reports/artifacts/UNIT-20261007/baseline*. Preserve dirty work.

Write only:
- src/test/java/org/lite/project/cashiersaas/unit/service/MerchantBusinessSettingsServiceTest.java
- src/test/java/org/lite/project/cashiersaas/unit/dto/MerchantBusinessSettingsMapperTest.java
- reports/TASK-033.md

Implement meaningful JUnit5 @Tag("unit") Mockito tests, without database/full app/server. Verify scoped owner guard for get/update and guard failures preventing persistence; valid normalized update changes tenant/outlet and records correct actor/outlet/before-after audit; currency change history conflict with no mutation/audit; same currency does not require history lookup; numeric scale-only no-op has no audit; invalid ISO/two-decimal currency, timezone, rate bounds/scale and names; reject insecure/credential/invalid-port logo; optional blank clears; JSON control escaping is parseable and legacy logo credentials not in audit; existing order snapshots are unaffected (test only if meaningful fixture available); explicit mapper values/null omission with Jackson3. Use actual entities/fixtures where behavior matters, not reflection-only assertions or implementation-mirroring tests. No production changes. Report discovered production defects, don't weaken tests.

First persist baseline/reservation/plan in report; update report after meaningful checkpoints and final with changed files/scenario list and exact test status. Do not run Maven/tests/build: orchestrator executes one serialized run after all workers submit, avoiding shared target races. No application/pom/SQL/canonical/other test edits, nested agents, git mutation, DB/network/secret operations. Finish READY FOR TEST EXECUTION.
