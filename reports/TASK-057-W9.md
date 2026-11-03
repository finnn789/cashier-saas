# TASK-057 W9 - v1alpha reflection test failure diagnosis

## Verdict
Neither a test-code artefact nor a production exposure. Root cause is a STALE BUILD ARTEFACT:
`target/test-classes/application.properties` (dated 23 Oct, left over from before commit 9f460fa, which removed
`src/test/resources/application.properties`). It shadows `src/main/resources/application.properties` on the test
classpath (test-classes precede classes), so the main config - including
`grpc.server.reflection-service-enabled=false` - was NOT loaded in the integration tests. net.devh then used
`matchIfMissing=true` and registered the v1alpha `ProtoReflectionService`.

Production config is correct. A clean build (`mvnw clean verify`) removes the stale file.

## Evidence
1. Failing run (stale file present), startup log:
   `Registered gRPC service: grpc.reflection.v1alpha.ServerReflection, bean: protoReflectionService, class: io.grpc.protobuf.services.ProtoReflectionService`
   Diagnostic after the await: `finished=false`, `response` was non-null and listed
   `cashier.v1.CashierService`, `grpc.health.v1.Health`, `grpc.reflection.v1alpha.ServerReflection`; `error=null`.
   The stream stayed open because the client never half-closed. The hang was therefore a real reflection response.
2. Temporary `Environment` probe in the test: `getProperty("grpc.server.reflection-service-enabled")` returned `null`
   (property absent from the test context).
   `target/test-classes/application.properties` contained only 4 `app.security.jwt.*` lines; `target/classes` has the correct file.
   `git log -- src/test/resources/application.properties` shows it was deleted in 9f460fa.
3. After deleting only that stale file (`target/test-classes/application.properties`, a build output, no source touched):
   probe printed `prop=[false]` from `class path resource [application.properties]`; startup registered only
   `cashier.v1.CashierService` and `grpc.health.v1.Health`; v1alpha result:
   `response=null error=UNIMPLEMENTED: Method not found: grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo`.
   Reflection tests: 2/2 pass.
4. Note: the v1 test (`serverReflectionV1IsNotExposed`) passed even in the broken state because
   `ProtoReflectionService` in grpc 1.85.1 only serves v1alpha; v1 requires `ProtoReflectionServiceV1`, which net.devh 3.1.0 never
   registers. That test is a valid negative check but did not detect the problem. The v1alpha test is the effective guard.

## Changes
- Test file: no net change. Temporary diagnostics (`W9DIAG` prints, injected `ConfigurableEnvironment`) were added and fully removed
  (verified by grep). The test still asserts no response and UNIMPLEMENTED for both v1alpha and v1.
- Deleted stale build artefact `target/test-classes/application.properties` (not tracked, regenerable by nothing - it was an orphan).
- No production code, pom, other tests or `.ai/*` touched. No commit.

## Final result
`GrpcSecurityIntegrationTest` (whole class, failsafe): Tests run: 22, Failures: 0, Errors: 0, Skipped: 0 - BUILD SUCCESS.
Reflection tests alone: 2/2 passed in the post-fix run (the failure before the fix was deterministic, not flaky).

## Recommendations for orchestrator
- Run the full `verify` after `clean` (or confirm `target/test-classes` has no orphan `application.properties`) to be sure the overall count is green.
- Optional hardening (needs a reservation): the test module relies on main `application.properties` being on the classpath;
  consider a reflection guard that does not depend on that, e.g. `@DynamicPropertySource`/`@SpringBootTest(properties=...)` is NOT recommended
  since it would hide a missing main property. Keep the test as is: it correctly fails when the main config is not applied.
