# TASK-035: real-time unit tests and checkout regression

Status: PAUSED BY USER (memory13), corrected source saved, final report/review pending. Memory baseline8. User explicitly authorized unit tests/delegation on 2026-10-07. Worker authenticated Codex CLI; Ruflo registration is coordination, not execution.

Read AGENTS.md, .ai/MEMORY.md, PRD active header, tasks/TASK-032.md, both TASK-032-REVIEW packets, docs/fnb-realtime.md and actual source. Role guidance .codex/agents/vertical_slice_builder.toml; explicit latest user delegation wins. Missing historical files must not be restored. Baseline copies/hashes reports/artifacts/UNIT-20261007/baseline*. Preserve dirty work.

Write only:
- src/test/java/org/lite/project/cashiersaas/unit/service/FnbRealtimeTicketServiceTest.java
- src/test/java/org/lite/project/cashiersaas/unit/service/FnbRealtimeAccessServiceTest.java
- src/test/java/org/lite/project/cashiersaas/unit/service/FnbRealtimePublisherTest.java
- src/test/java/org/lite/project/cashiersaas/unit/service/PublicOrderServiceTest.java
- src/test/java/org/lite/project/cashiersaas/unit/component/FnbRealtimeHandshakeInterceptorTest.java
- src/test/java/org/lite/project/cashiersaas/unit/component/FnbRealtimeWebSocketHandlerTest.java
- src/test/java/org/lite/project/cashiersaas/unit/component/FnbOrderChangedListenerTest.java
- src/test/java/org/lite/project/cashiersaas/unit/configuration/FnbWebSocketConfigurationTest.java
- src/test/java/org/lite/project/cashiersaas/unit/dto/FnbRealtimeMapperTest.java
- reports/TASK-035.md

JUnit5 @Tag("unit") Mockito; no DB/server/fullBoot startup. Cover ticket issuance/format/single use, expiry distinction/cap at JWT, malformed/header/principal mismatch/expired/revoked denial, perprincipal capacity and release. Deterministic expiry via fixtures/reflection where needed; no timing sleeps/flaky tiny TTL. Access service current session/merchant/membership/role/expiry and cross-scope rejection. Handshake exact one app protocol+one ticket protocol, reject URIquery/missing/duplicate/malformed/reused ticket. Handler exact tenant/outlet delivery, JWT expiry/revocation closes, bounded executor rejection containment, no client mutation, minimal JSON snapshot, broken socket isolated, cleanup releases. Publisher immutable snapshot; listener failure containment and meaningful AFTER_COMMIT versus rollback test using small Spring transaction synchronization infrastructure and no DB (not just annotation presence). Configuration invalid origin paths/wildcards/credentials/ports and bounds where accessible; no threads leaked. Mapper absent null/sensitive fields. Update existing PublicOrderServiceTest only for added publisher injection; assert successful checkout publishes once and replay/failed checkout don't publish. Preserve all prior assertions.

Do not modify production to facilitate tests. Report defects to orchestrator; no weakening expected behavior. Persist report before writing and at checkpoints/final with scenarios/exact changed paths and remaining gaps. Do not run tests/Maven/build; orchestrator executes once after workers finish. No other production/pom/SQL/canonical/tests, nested agents, git, DB/network/secret operations. Final READY FOR TEST EXECUTION.
