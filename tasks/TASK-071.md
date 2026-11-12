# TASK-071: Map a lost optimistic-lock race to HTTP 409 instead of 500

Status: DELEGATED (2026-11-11). Memory version at delegation: 65. Branch `feature/fnb-stock-concurrency-tests` @ `be90deb` (TASK-070 still uncommitted on the same branch; do not touch its files). User approval: the user explicitly approved this public-behaviour change on 2026-11-11 and asked for the handler to be written by a worker. Tests: the worker WRITES the test below but nobody runs Maven now (policy: tests run only on the user's request).

## Why
`MutableBaseEntity` carries `@Version`. When two transactions update the same row, the loser fails with Spring's `ObjectOptimisticLockingFailureException` (subclass of `org.springframework.dao.OptimisticLockingFailureException`). `GlobalExceptionHandler` has no handler for it, so it falls into `handleUnexpected(Exception)` and the client gets HTTP 500 plus an error log. It is a normal, retryable conflict (transaction already rolled back, nothing persisted), so it must be a 409.

## Reserved writes (ONLY these)
- `src/main/java/org/lite/project/cashiersaas/exception/GlobalExceptionHandler.java` (edit: add ONE handler)
- `src/test/java/org/lite/project/cashiersaas/exception/GlobalExceptionHandlerTest.java` (NEW; no handler test exists today, verified by the orchestrator)
- `reports/TASK-071-W1.md`

## Contract
Add to `GlobalExceptionHandler`, next to `handleOperationConflict` / `handleDatabaseConflict`:

```java
@ExceptionHandler(OptimisticLockingFailureException.class)   // org.springframework.dao
@ResponseStatus(HttpStatus.CONFLICT)
public ApiResponse<Object> handleOptimisticLockConflict(OptimisticLockingFailureException exception) {
    log.warn("Operation rejected because the data was changed concurrently.");
    return ApiResponse.error(HttpStatus.CONFLICT.value(),
            "Data baru saja diubah oleh proses lain. Silakan coba lagi.");
}
```

Rules for the handler: catch the Spring parent type `OptimisticLockingFailureException` (covers `ObjectOptimisticLockingFailureException`); no new exception class, no new file in `src/main`, no `ResponseEntity`, no wildcard generics, no `@Deprecated`; do not log the exception object, entity ids or stack trace (WARN with a fixed message only); keep the existing generic `Exception` handler untouched and below nothing special (Spring picks the most specific type, order does not matter). Do not touch any other handler.

## Test (new class, plain JUnit 5, no Spring context, no Docker)
Look at one existing unit test under `src/test/java/org/lite/project/cashiersaas/unit/**` or `.../service/**` for the style (JUnit 5, AssertJ or the assertions it already uses). Cover:
1. Direct call: `handler.handleOptimisticLockConflict(new ObjectOptimisticLockingFailureException(SomeEntity.class, "id"))` returns an `ApiResponse` whose code is 409, `success` is false, message is the exact Indonesian text above. Use the real `ApiResponse` getters; check its fields first.
2. HTTP mapping with MockMvc `standaloneSetup` + `.setControllerAdvice(new GlobalExceptionHandler())` and a tiny `@RestController` static nested class inside the test whose method throws `ObjectOptimisticLockingFailureException` : status is 409 and the JSON body has code 409 (use `jsonPath`). Add a second route in the same stub throwing `new RuntimeException("x")` and assert it is STILL 500 (the generic handler is intact). If standalone MockMvc needs a JSON converter that is not on the classpath, check what other tests already do and follow it; if impossible without adding a dependency, keep only check 1 plus the `@ResponseStatus` annotation check via reflection (`getMethod(...).getAnnotation(ResponseStatus.class).value() == CONFLICT`) and say so in the report.

## Rules
- You inherit no conversation. Read this file, `AGENTS.md`, `GlobalExceptionHandler.java`, `shared/response/ApiResponse.java` and one existing unit test for style.
- DO NOT RUN MAVEN or any build/test; no git commands; edit nothing except the reserved paths (no `.ai/*`, `tasks/*`, `postman/**`, no other tests, not `FnbCatalogService.java`, which carries the user's unrelated uncommitted work). Re-read your code for compile errors (imports, package names).
- Report `reports/TASK-071-W1.md`: changed files, exact assertions, evidence of what you re-read, risks, deviations.

## Orchestrator acceptance
Review the diff against this contract and `AGENTS.md` (DTO/handler rules). Compile only (`mvnw.cmd -q -B -DskipTests test-compile`, build without `clean`). Test execution waits for the user's request. Existing concurrency tests (TASK-067/070) need NO change: they only assert `< 500`/`>= 400 && < 500`, and with the tenant lock intact no 409 from this handler occurs; verified by reading, not by running.
