# TASK-057 W8 - Test Run Report

Scope: run and report only. No source or test files edited. No commits. No .env files read.

## Environment
- Docker: `docker info` succeeded (server 29.4.3).
- Command: `.\mvnw.cmd -B verify` in `E:\Java\cashier-saas` (run in background).
- Full console log: `C:\Users\arips\AppData\Local\Temp\claude\E--Java-cashier-saas\f2aea72e-21dc-4bb9-b4df-874ad0ebe8b9\scratchpad\w8-verify.log`
- Maven total time: 54.531 s (wall time includes dependency resolution and compile before tests; not separately measured).

## Result
**BUILD FAILURE** (failsafe `verify` goal failed: "There are test failures").

| Suite | Run | Pass | Fail | Error | Skipped |
|---|---|---|---|---|---|
| Surefire (unit) | 286 | 286 | 0 | 0 | 0 |
| Failsafe (integration) | 32 | 31 | 1 | 0 | 0 |

## Failsafe per-class results
| Class | Tests | Result |
|---|---|---|
| CashierSaasApplicationTests | 1 | PASS |
| FnbPayFirstFlowIntegrationTest | 5 | PASS |
| GrpcSecurityIntegrationTest | 22 | 21 PASS, 1 FAIL |
| SecurityAuthorizationIntegrationTest | 4 | PASS |

## Surefire unit classes (all PASS, 0 fail/error/skip)
Selected classes relevant to TASK-057:
- unit.grpc.CashierGrpcHandlerRejectionTest: 14
- unit.grpc.GrpcRateLimiterTest: 8
- unit.grpc.GrpcSecurityPropertiesValidationTest: 10
- unit.grpc.GrpcServiceKeyInterceptorTest: 12
- unit.grpc.GrpcTenantAccessPolicyTest: 42
- unit.component.FnbRealtimeHandshakeInterceptorTest: 17
- unit.component.FnbRealtimeWebSocketHandlerTest: 12
- unit.service.FnbRealtimeAccessServiceTest: 18
- unit.service.MerchantAccessGuardTest: 5
- All other surefire classes: PASS (see `target/surefire-reports`).

## Failures

### 1. GrpcSecurityIntegrationTest.serverReflectionV1AlphaIsNotExposed (line 355)
Message:
```
org.opentest4j.AssertionFailedError: reflection call did not finish ==> expected: <true> but was: <false>
	at org.junit.jupiter.api.Assertions.assertTrue(Assertions.java:228)
	at org.lite.project.cashiersaas.integration.grpc.GrpcSecurityIntegrationTest.serverReflectionV1AlphaIsNotExposed(GrpcSecurityIntegrationTest.java:355)
```
Test took 10.03 s (the 10 s `CountDownLatch` await timed out).

Analysis: the test opens a `ServerReflectionV1Alpha` bidi stream with valid headers, sends `listServices`, and expects `onError` with `UNIMPLEMENTED` (reflection must not be exposed). Neither `onError` nor `onCompleted` fired within 10 s, so the server neither rejected nor closed the stream. No "Caused by" line appears in the captured output. The sibling test `serverReflectionV1IsNotExposed` passed, so the v1 path behaves differently from v1alpha. Root cause not yet determined; this needs a worker or orchestrator investigation of how the server handles the v1alpha reflection service (e.g. whether the reflection service is registered, or whether a stream with no response hangs rather than returning UNIMPLEMENTED).

## Other notes
- `target/failsafe-reports` also contains stale XML from earlier runs that were NOT executed in this run: `fnb.TmpDebugIT` (last written 10/31/2026) and surefire `unit.service.CashierSaleServiceTest` (last written 10/26/2026). They are not counted above; Maven's totals (surefire 286, failsafe 32) match the fresh runs only. The `TmpDebugIT` file should be checked and removed if it is a leftover debug test.
- No `Caused by` / Spring context startup failure appeared in the log. `CashierSaasApplicationTests` passed, so the application context started.
- Docker containers (`docker ps -a`) at time of check: `flowable-ui` (flowable/flowable-ui:latest), `rag-redis` (redis:7.4-alpine), `rag-qdrant` (qdrant/qdrant:v1.12.4). None of these were created by this run and none were stopped. No project postgres container was left running in the listing taken during the run; re-check after completion before handoff.

## Conclusion
Verify is NOT green. 1 failsafe test fails (GrpcSecurityIntegrationTest.serverReflectionV1AlphaIsNotExposed). All other 317 executed tests pass. Do not mark TASK-057 as passing until this failure is resolved and the build is re-run.
