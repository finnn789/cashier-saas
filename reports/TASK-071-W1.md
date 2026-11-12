# TASK-071 W1 report

Status: implemented, NOT compiled or run (policy: workers never run Maven).

## Changed files
- `src/main/java/org/lite/project/cashiersaas/exception/GlobalExceptionHandler.java`: added import `org.springframework.dao.OptimisticLockingFailureException` and handler `handleOptimisticLockConflict` (exactly the contract text), placed between `handleDatabaseConflict` and `handleBusinessProfileAlreadyCompleted`. No other handler touched.
- `src/test/java/org/lite/project/cashiersaas/exception/GlobalExceptionHandlerTest.java` (new).
- `reports/TASK-071-W1.md` (this file).

## Assertions (all `@Tag("unit")` so default surefire `test.groups=unit` picks them up; plain JUnit 5 `Assertions`, same as other unit tests)
1. `optimisticLockFailureIsReturnedAsConflictResponse`: direct call with `new ObjectOptimisticLockingFailureException(Object.class, "id")`; `getCode()==409`, `isSuccess()==false`, `getMessage()` equals "Data baru saja diubah oleh proses lain. Silakan coba lagi.".
2. `optimisticLockHandlerDeclaresConflictStatus`: reflection, `@ResponseStatus.value() == HttpStatus.CONFLICT`.
3. `optimisticLockFailureIsMappedToHttp409`: MockMvc `standaloneSetup(stub).setControllerAdvice(handler)`; GET `/lock` -> status 409, `$.code`=409, `$.success`=false, `$.message`=the text.
4. `unexpectedExceptionIsStillHttp500`: GET `/boom` throws `RuntimeException("x")` -> 500, `$.code`=500.

## Evidence (re-read)
`GlobalExceptionHandler.java`, `ApiResponse` (getters via Lombok `@Getter`; `success` boolean -> `isSuccess()`; `@JsonInclude NON_NULL`), `PhoneNumberNormalizerTest` for style (`@Tag("unit")`, `org.junit.jupiter.api.Assertions`), `pom.xml` (surefire groups `unit`, `spring-boot-starter-webmvc-test` available for MockMvc/jsonPath). Existing tests use MockMvc only via `webAppContextSetup`; none use `standaloneSetup`, so check 3/4 are the part most worth confirming when tests are run.

## Risks / deviations
- Not compiled. Possible issue: standalone MockMvc relies on a default JSON converter for `ApiResponse` (Spring Boot 4 / Jackson 3 `tools.jackson`, `LocalDateTime` timestamp); should work with Spring 7 defaults but unverified. If it fails, fall back to checks 1 and 2 only.
- Test path follows the task (`.../cashiersaas/exception/`), not the `unit/**` folder; the `@Tag("unit")` keeps it in the default unit run.
- Stub controller methods return `String` and always throw; the `ObjectOptimisticLockingFailureException` is built with `Object.class` as entity type.
- No deviations from the handler contract.
