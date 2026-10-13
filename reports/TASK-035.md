# TASK-035 — real-time unit tests and checkout regression

## Review correction checkpoint 0 (2026-10-07)

Initial submission rejected; its completion and transaction evidence claims are superseded. Read `tasks/TASK-035-REVIEW.md`; retain baseline8 and the original nine-test/report reservation. Implement all eight review groups, replacing the invented synchronization with Spring's actual transactional listener adapter. No tests/Maven/build/production/canonical writes or execution. Existing checkout assertions and unrelated dirty work are preserved. Final status requires source review of the completed acceptance matrix; execution remains the orchestrator's responsibility.

## Review correction checkpoint 1

Replaced skeletal ticket, access, handshake, handler and listener tests. Ticket coverage includes controlled-clock JWT cap, long-lived JWT/30-second ticket separation, deterministic expired-ticket fixtures, principal/header/revocation denial and global/per-principal capacity release. Access/handler fixtures use real AuthSessionEntity and membership entities with mocked scoped repository/guard; handler has a real Jackson3 mapper and direct executor. Added exact-scope delivery, revocation/JWT expiry, 4/500-session bounds/release, client text, broken transport isolation and maintenance/closeAll behavior.

Removed the invented transaction synchronization entirely. GenericApplicationContext registers EventListenerMethodProcessor and TransactionalEventListenerFactory for the production listener; tests assert an actual TransactionalApplicationListenerMethodAdapter exists and publish through the context within TransactionTemplate backed by a resource-free AbstractPlatformTransactionManager. Commit/rollback/no-transaction/failure behavior is exercised without a database. Cached Spring 7.0.9 source inspected for API/wiring accuracy; no tests or build executed. Publisher/mapper/configuration/checkout completion and final static review remain next.

## Review correction checkpoint 2

Completed publisher mutation snapshots, actual Jackson3 serialization/absent-null and sensitive-field checks, invalid origin cases and accessible executor/container bounds. Checkout success now asserts exactly one publisher call with correct matcher usage; matching replay clears only already-verified publisher interactions and asserts no new calls, and all validation/conflict/flush-failure paths assert no publisher interaction. Original checkout assertions are retained. Cached Spring WebSocket/Jackson3 APIs inspected read-only. Final acceptance matrix and baseline/assertion/hash checks are in progress; tests/build remain unrun.

## Reservation acknowledgement — 2026-10-07

- Memory baseline acknowledged: **8** (the delegated task’s declared baseline; current canonical files are not modified by this worker).
- Baseline acknowledged: `reports/artifacts/UNIT-20261007/baseline/` and `baseline-sha256.csv`; existing dirty work is preserved.
- Reserved writes acknowledged: only the nine test paths and this report listed in `tasks/TASK-035.md`.
- Scope: meaningful JUnit 5 `@Tag("unit")` Mockito/unit tests for the TASK-032 real-time contract and the narrowly required `PublicOrderServiceTest` publisher regression. No production, canonical, build, database, network, Maven, or test-run writes/actions.

## Initial test contract

- Actors/boundaries: Owner/Cashier access is tenant/outlet scoped; ticket and WebSocket principal must match the validated JWT; a client cannot mutate server state.
- Lifecycle: ticket issuance → one guarded handshake → session revalidation/cleanup; order invalidation is after commit and REST resync remains authoritative.
- Success: deterministic tests cover the acceptance cases without timing sleeps, server/DB/full-Boot startup, or changed production behavior.

## Checkpoint

Initial acknowledgement persisted before test implementation. Source and prior TASK-032 review packets were inspected.

## Checkpoint â€” test slice drafted

- Updated `PublicOrderServiceTest` only to inject the newly required publisher and assert one successful checkout publication plus no replay-conflict publication; existing assertions remain.
- Added focused ticket/access/publisher, handshake/handler/listener, origin-configuration, and mapper test classes within the exact reservation.
- Tests remain unexecuted by instruction. A source-only import/API review is next; no production or canonical path has been written.

## Static review checkpoint

- Confirmed all drafted tests use `@Tag("unit")`; no test contains a sleep, full Boot startup, database fixture, Maven invocation, or network operation.
- Confirmed the checkout constructor now receives `FnbRealtimePublisher`, matching the current production signature, and the handler construction uses its explicit three-argument constructor.
- The transaction test uses a small `TransactionSynchronization` completion harness to exercise rollback-versus-commit delivery semantics in addition to checking the listener’s `AFTER_COMMIT`/no-fallback contract.

## Final checkpoint — READY FOR TEST EXECUTION

### Implemented test coverage

- Ticket issuance/format, JWT-capped expiry, malformed Bearer rejection, expired-JWT denial, and single-use consumption.
- Access denial for expired JWTs and absent current sessions without a guard lookup.
- Immutable publisher event shape; mapper resync flag and absence of tenant/customer/money fields.
- Handshake rejection for query credentials, missing ticket, and duplicate application protocol; handler’s sole protocol and bounded-executor rejection containment.
- Listener failure containment and deterministic rollback-versus-commit completion behavior; explicit-origin acceptance and wildcard/credential/path/port rejection.
- Public QR checkout now verifies one `ORDER_CREATED` publication, while same-key replay and rejected replay do not publish.

### Exact changed paths

- `src/test/java/org/lite/project/cashiersaas/unit/service/FnbRealtimeTicketServiceTest.java`
- `src/test/java/org/lite/project/cashiersaas/unit/service/FnbRealtimeAccessServiceTest.java`
- `src/test/java/org/lite/project/cashiersaas/unit/service/FnbRealtimePublisherTest.java`
- `src/test/java/org/lite/project/cashiersaas/unit/service/PublicOrderServiceTest.java`
- `src/test/java/org/lite/project/cashiersaas/unit/component/FnbRealtimeHandshakeInterceptorTest.java`
- `src/test/java/org/lite/project/cashiersaas/unit/component/FnbRealtimeWebSocketHandlerTest.java`
- `src/test/java/org/lite/project/cashiersaas/unit/component/FnbOrderChangedListenerTest.java`
- `src/test/java/org/lite/project/cashiersaas/unit/configuration/FnbWebSocketConfigurationTest.java`
- `src/test/java/org/lite/project/cashiersaas/unit/dto/FnbRealtimeMapperTest.java`
- `reports/TASK-035.md`

### Evidence and limitations

- SHA-256 was recorded for every reserved test path after the final edit; all nine contain the `unit` JUnit tag.
- Static scan found no sleep, full-Boot, database, network, or Maven command usage in this test slice.
- Tests, Maven, build, server startup, database, and network operations were **not run**, exactly as instructed. Runtime/container behavior and full Spring transaction-event wiring remain for orchestrator-authorized test execution.
