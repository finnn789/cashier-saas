# TASK-059 Section E report: Java MemberSyncClient

Status: implementation and tests WRITTEN, NOT RUN (Maven forbidden for this worker; the orchestrator compiles and runs).

## Changed files

Main (new):
- `src/main/java/org/lite/project/cashiersaas/configuration/AiServiceProperties.java` (`app.ai-service.*`: base-url, api-key, connect/read timeout 2s/5s, max-attempts 3, retry-backoff 200ms,800ms; `isEnabled()` = base-url not blank)
- `src/main/java/org/lite/project/cashiersaas/service/MemberSyncClient.java` (Spring `RestClient` over `JdkClientHttpRequestFactory`, HTTP/1.1; `sync(event)` never throws; retry I/O + 5xx only; no body/phone/key/exception message in logs)
- `src/main/java/org/lite/project/cashiersaas/service/MemberSyncPublisher.java` (publishes the event inside the tx; skips + WARN with ids when the user has no phone)
- `src/main/java/org/lite/project/cashiersaas/component/MemberSyncRequestedEvent.java` (immutable snapshot; `toString` shows ids and role only)
- `src/main/java/org/lite/project/cashiersaas/component/MemberSyncListener.java` (`@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=false)`, dispatches to a bounded daemon pool of 2, queue 200)

Main (edited):
- `service/FreeTierOnboardingService.java`: new field `MemberSyncPublisher`; publish in `complete(...)` on the first completion AND on the idempotent already-active branch.
- `service/CashierInvitationService.java`: new field `MemberSyncPublisher`; publish in `claim(...)` on the normal path AND on the already-claimed branch.
- `src/main/resources/application.properties`: `app.ai-service.base-url=${AI_SERVICE_BASE_URL:}`, `api-key=${AI_INTERNAL_API_KEY:}`, timeouts, max-attempts.

Tests (new only; no existing test edited, `FreeTierOnboardingServiceTest` only uses `startFreeTier` so the extra constructor dependency is null there and unused):
- `src/test/java/.../unit/service/MemberSyncClientTest.java` (MockRestServiceServer + contract fixture, header, retry 5xx/IO, no retry 4xx/3xx, give up after 3, backoff timing, disabled (blank url, blank key), never throws, log content)
- `src/test/java/.../unit/service/MemberSyncPublisherTest.java`
- `src/test/java/.../unit/component/MemberSyncListenerTest.java` (real Spring transactional adapter: commit / rollback / no tx / disabled / rejected executor)
- `src/test/java/.../integration/identity/MemberSyncIntegrationTest.java` (JDK HttpServer stub; owner, cashier + idempotent re-claim, 500 x3 not breaking onboarding, 422 no retry, heal via idempotent re-complete)
- `src/test/java/.../integration/identity/MemberSyncDisabledIntegrationTest.java` (blank base-url forced; `@MockitoSpyBean` verifies `sync` never called)

## Decisions / deviations from the contract
1. The push runs asynchronously on a small bounded pool (listener) so retries (up to ~16 s worst case) never block the HTTP response. Contract does not forbid it; the client itself is synchronous and unit-testable.
2. Blank `api-key` with a non-blank `base-url` disables the sync with a WARN (instead of sending unauthenticated calls that Ai-services would reject with 401). Contract only specifies blank base-url.
3. 3xx responses are treated as failure without retry (no redirect following).
4. Extra properties beyond the contract: connect-timeout, read-timeout, max-attempts, retry-backoff (defaults are the contract values).

## Test status
Written, not run. Nothing was compiled.

## Remaining / orchestrator double-checks
- Compile: `MemberSyncClient` has two public constructors (`@Autowired` on the one-arg); `JdkClientHttpRequestFactory.setReadTimeout(Duration)`; `MockRestServiceServer.bindTo(RestClient.Builder)`; `org.springframework.test.context.bean.override.mockito.MockitoSpyBean` (Spring 7); logback classes in the unit test.
- Integration: relies on `app.ai-service.retry-backoff=20ms,40ms` binding to `List<Duration>`; heal test relies on the existing idempotent re-complete (same profile payload) returning 200; cashier flow uses register -> intent CASHIER_ONLY -> owner invitation -> claim.
