# TASK-032 worker report

## Checkpoint 0 — pre-code acknowledgement (2026-10-07)

- Role: implementation worker only; no canonical `.ai` writes, nested agents, tests, build, startup, database, network, or secret reads.
- Memory baseline acknowledged: `.ai/MEMORY.md` version 5.
- Reservation acknowledged: `tasks/TASK-032.md`; writes are limited to its exclusive allowlist.
- Baseline acknowledged: `reports/artifacts/MVP-20261007/baseline-sha256.csv`; the repository already has extensive unrelated dirty changes, which will be preserved.
- Reviewed architecture brief: `.ai/context/settings-audit-realtime-20261007.md`. Contract selected: native Spring WebSocket ticket handshake, 30-second single-use bounded in-memory ticket state, scoped after-commit F&B invalidations, REST resync fallback.
- Acceptance focus: Owner/Cashier ticket API scoped through current guard; ticket is supplied only as WebSocket subprotocol; current access is rechecked on handshake and delivery; minimal immutable order snapshots publish only after commit; replay paths do not publish.
- Static-only work: no tests/build/startup will be run by task and repository policy.

## Checkpoint 1 — implementation complete, pending static review

- Added the native WebSocket dependency, exact anonymous GET upgrade route, explicit localhost origins, ticket controller/service, guarded interceptor, bounded session handler, transaction-bound listener, event mapper/DTOs, and operator documentation.
- Added publish hooks after actual QR/POS creation, payment settlement, mock pending/failed/settled outcomes, discount, cancellation, and kitchen transitions. Existing idempotent replay returns remain before their matching publish paths.
- The publisher only depends on `ApplicationEventPublisher`; the listener fires after commit with `fallbackExecution=false`, catches delivery failures, and delivery access checks use a separate `REQUIRES_NEW` read-only service call.
- No schema, entities, tests, shared principal, canonical state, or non-allowlisted application files were written.

## Final checkpoint — READY FOR TESTING

### Delivered contract

- `POST /api/v1/cashier/outlets/{outletId}/fnb/realtime-ticket` requires the existing Owner/Cashier route policy and `MerchantAccessGuard`. It re-decodes the submitted Bearer token with the configured `JwtDecoder`, converts it with the existing converter, and requires an exact current-principal match before issuing a 32-byte random, SHA-256-digested, one-use ticket. Expiry is the earlier of JWT expiry and 30 seconds.
- Native WebSocket path is `GET /api/v1/fnb/realtime`, with only `cashier-fnb-v1` negotiated. The ticket is accepted exclusively as `ticket.<ticket>` in `Sec-WebSocket-Protocol`; it is never put in a URL or logged. The exact route is anonymous only for the upgrade; the interceptor consumes and authorizes the ticket.
- Ticket storage is bounded to 2,000 entries; sessions are bounded to 500 globally and 4 per user/outlet. Session sends use Spring's bounded `ConcurrentWebSocketSessionDecorator` (5 s / 16 KiB). Expired tickets are opportunistically cleaned on issue/consume; expired/revoked access is checked and closed before delivery.
- Events are immutable in-process snapshots published inside the business transaction and delivered only with `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=false)`. Delivery exceptions are contained after commit. Payload is only `eventId`, `type`, `orderId`, `outletId`, order/payment state, timestamp, and `resyncRequired=true`.
- Event hooks cover actual QR/POS creation, manual/mock settlement, mock pending/failed outcomes, discount, cancellation, and kitchen preparing/ready/completed. Existing idempotent replay returns precede all hooks.
- REST fallback behavior was not changed. `docs/fnb-realtime.md` documents ticket use, subprotocol connection, reconnect, and REST resync.

### Actual task files changed

- `pom.xml`, `configuration/SecurityConfiguration.java`, `configuration/FnbWebSocketConfiguration.java`
- `component/FnbOrderChangedEvent.java`, `component/FnbOrderChangedListener.java`, `component/FnbRealtimeHandshakeInterceptor.java`, `component/FnbRealtimeWebSocketHandler.java`
- `service/FnbRealtimeTicketService.java`, `service/FnbRealtimeAccessService.java`, `service/FnbRealtimePublisher.java`, plus the seven reserved F&B operation services
- `controller/FnbRealtimeController.java`, `dto/FnbRealtimeMapper.java`, the two reserved realtime response DTOs, `docs/fnb-realtime.md`, and this report.

### Static review and limits

- `git diff --check` completed without whitespace errors. Reserved realtime source has no DTO records, wildcard generic declarations, or `ResponseEntity`; the one framework-mandated `Map<String,Object>` is documented and isolated in the handshake adapter.
- Review confirmed ticket principal/outlet scoping, explicit origins, after-commit-only delivery, minimal payloads, and replay non-emission paths. No tests, build, startup, database, or network commands ran, as required.
- Transport is intentionally best-effort within one JVM: there is no persisted replay, cluster fan-out, or background scheduler. Ticket cleanup is opportunistic at issue/handshake, while delivery closes expired or revoked sessions; clients must reconnect then REST-resync.

### Recommended later verification

- Unit tests for ticket expiry/single-use/bounds, JWT-principal mismatch, and current-access revocation.
- WebSocket API tests for wrong protocol/missing/reused ticket rejection, origin rejection, scoped event delivery, payload shape, and idempotent no-event behavior.
- End-to-end test for every listed mutation reaching a connected authorized Owner/Cashier only after commit, followed by REST resync.

## Revision checkpoint 0 — review acknowledged (2026-10-07)

- Read `tasks/TASK-032-REVIEW.md`; the original implementation is `NEEDS_REVISION` and this report is updated before revised code.
- Scope remains exactly the TASK-032 allowlist. Concurrent TASK-030/031 and pre-existing dirty files remain preserved.
- Required corrections accepted: Boot 4 Jackson 3 adapter, separate ticket/JWT connection expiry, atomic global/per-principal bounds, current auth-session + membership validation, strict protocol parsing, periodic lifecycle maintenance, configurable explicit origins, and bounded asynchronous delivery.
- Static-only revision: no tests, build, startup, database, network, secret reads, canonical writes, or nested agents.

## Revision final checkpoint — READY FOR TESTING

- Replaced Jackson 2 imports with the existing Boot 4 `tools.jackson.databind.ObjectMapper` bean and its established `writeValueAsBytes` path.
- Ticket expiry and connection access expiry are distinct. A ticket is accepted only before its 30-second/JWT-capped ticket expiry; established sessions are valid through JWT expiry and current access only.
- Ticket insertion is atomically bounded at 2,000 total and four outstanding tickets per user/tenant/outlet. Session insertion is atomically bounded at 500 total and four per principal; database checks and socket writes occur outside those locks.
- `FnbRealtimeAccessService` now verifies current JWT expiry, `AuthSessionRepository.findById(sessionId,userId)`, usable non-revoked session, selected tenant/outlet/membership/role, and current guard membership/role in a read-only `REQUIRES_NEW` transaction.
- Handshakes reject query credentials, missing/duplicate ticket protocols, missing/duplicate application protocol, expired/revoked access, and do a final access check before session registration. Only `cashier-fnb-v1` is negotiated.
- Configuration now owns a bounded reject-on-overflow delivery executor, a five-second lifecycle scheduler for ticket cleanup/session revalidation, finite container idle/send/buffer bounds, explicit configurable origins via `app.fnb.realtime.allowed-origins`, and shutdown-managed executors. The handler closes sessions at shutdown.
- After-commit listener enqueues delivery; queue overflow/drop and all delivery failures cannot surface to the already-committed caller. REST polling/resync remains unchanged.

### Static evidence

- `git diff --check` completed with no whitespace errors. Review found no Jackson 2 imports, wildcard generic declarations, response entities, or records in revised realtime adapters/services.
- No tests, build, startup, database, network, or secret operations were run.
- Current SHA-256: `FnbWebSocketConfiguration` `FCBE55C54FAF23152B3AB5DB116C0680570B9853262E69C8F51D84D67DCF3F60`; `FnbRealtimeWebSocketHandler` `BC6971C37900F00FFB3EE3FCC6FE0FD3E35463F567D03AAFD4A6A6D7BC64DA44`; `FnbRealtimeTicketService` `6B453B4A8C4ACCC40B1B8B3A364C6D60552088B9566E937A0563FBD8E7EBFA7D`; `FnbRealtimeAccessService` `7631504DB81767CA566EBC3612985FBAA834A3C9E27BB234A2744C685E35712E`; `FnbRealtimeHandshakeInterceptor` `7926940858EFE90FCECC7CD95F1EAC8DA6712E7021C628FDFAC586BF4A2337C4`; `SecurityConfiguration` `E1B18540B5354CB1CD0574BB2A6B9E082B2AB7BA9C52DA606C8A8F4DA307E6C1`.

### Recommended focused verification

- Ticket/JWT lifetime separation, atomic ticket/session limits, session revocation or merchant-switch closure, and malformed/duplicate protocol rejection.
- Explicit-origin acceptance/rejection, executor overload drop with REST resync, scheduler cleanup, and post-commit-only scoped event delivery.

## Revision 2 checkpoint 0 — adapter defects acknowledged (2026-10-07)

- Read `tasks/TASK-032-REVIEW-2.md`; prior READY FOR TESTING checkpoints are historical and superseded by this correction cycle.
- Current canonical-memory reference is version 7; the worker baseline remains version 5 and the TASK-032 exclusive allowlist is unchanged.
- This pass corrects only source-level Jackson 3 exception handling, explicit qualified injection, configuration-cycle avoidance, per-session runtime failure containment, strict origin validation, scheduler resiliency, and formatting.
- Static-only: no tests, build, startup, database, network, secrets, canonical writes, or nested agents.

## Revision 2 final checkpoint — READY FOR TESTING

- `FnbRealtimeWebSocketHandler` now catches the confirmed unchecked Jackson 3 `tools.jackson.core.JacksonException`, rather than an unreachable `IOException` catch around serialization.
- Handler construction is explicit: `@Qualifier("fnbRealtimeDeliveryExecutor")` is on the constructor parameter, with no Lombok-generated constructor ambiguity. The delivery executor is a static no-state bean factory, removing the configuration-to-handler-to-executor creation cycle.
- Session capacity is still atomically reserved under its narrow lock, but every socket close occurs after the lock. Per-session access, send, decorator, and close failures are contained and release that session without aborting other delivery or maintenance work.
- The periodic scheduler catches unexpected runtime failures around every maintenance tick; explicit-origin validation now rejects port zero/out-of-range and every nonempty path, including `/`.
- Existing original/revision READY FOR TESTING sections are historical submissions; this section supersedes them. No compile/startup/test success is claimed.

### Revision 2 static evidence and hashes

- `git diff --check` completed without whitespace errors. Source review confirms no Jackson 2 imports in the revised handler, no Lombok required-args constructor there, and no network operation under either capacity lock.
- No test, build, startup, database, or network command ran. Remaining uncertainty is limited to runtime/container integration, intentionally deferred to user-authorized testing.
- Current SHA-256: `FnbWebSocketConfiguration.java` `C88565BC167FE2E21AAE3178BC30324E5C221F0BAAA1F223A15F620D90873031`; `FnbRealtimeWebSocketHandler.java` `32BBD9BE992EE74753E117D8F1C764D804F79893A82458A954296A8F229AF6D2`; `FnbRealtimeTicketService.java` `6B453B4A8C4ACCC40B1B8B3A364C6D60552088B9566E937A0563FBD8E7EBFA7D`; `FnbRealtimeAccessService.java` `7631504DB81767CA566EBC3612985FBAA834A3C9E27BB234A2744C685E35712E`; `FnbRealtimeHandshakeInterceptor.java` `7926940858EFE90FCECC7CD95F1EAC8DA6712E7021C628FDFAC586BF4A2337C4`; `SecurityConfiguration.java` `E1B18540B5354CB1CD0574BB2A6B9E082B2AB7BA9C52DA606C8A8F4DA307E6C1`; `docs/fnb-realtime.md` `EF3137CC1EBAE3F2A8AA5A09CDAECD7D761B00F31CB8C54EE008E0F17223EDE1`.
