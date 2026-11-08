# TASK-059 Worker A report: Java staff phone number

Status: implementation and tests WRITTEN, NOT RUN (no Maven/build executed by instruction; orchestrator compiles and runs).

## Changed files

Main:
- `src/main/resources/db/migration/V3__staff_user_phone_number.sql` (new): `ALTER TABLE staff_users ADD COLUMN phone_number varchar(20);` nullable, no unique constraint.
- `src/main/resources/db/demo/R__demo_seed.sql`: demo owner phone `6281200000001`, demo cashier `6281200000002` in the INSERT, plus two `UPDATE ... WHERE phone_number IS NULL` statements so already-seeded demo databases get the phone (repeatable migration checksum changes, so Flyway re-runs it). Still idempotent.
- `src/main/java/.../entity/shared/identity/StaffUserEntity.java`: field `phoneNumber` (`phone_number`, length 20, Lombok getter via class `@Getter`); `registerWithPassword(email, hash, fullName, normalizedPhoneNumber)` and `createCashier(email, hash, fullName, normalizedPhoneNumber)` (old 3-arg signatures removed, no compat overloads).
- `src/main/java/.../dto/request/identity/RegisterUserRequest.java`: `phoneNumber` with `@NotBlank` ("Nomor telepon wajib diisi.") and `@Size(max = 32)` ("Nomor telepon maksimal 32 karakter.").
- `src/main/java/.../service/RegisterService.java`: normalizes via `PhoneNumberNormalizer.normalize` FIRST (before the email lookup), stores the normalized value.
- `src/main/java/.../shared/PhoneNumberNormalizer.java` (new): `public static String normalize(String)` (throws existing `InvalidOperationException("Nomor telepon tidak valid.")`, mapped to 400 by existing `GlobalExceptionHandler`) and `public static Optional<String> tryNormalize(String)`. Implements contract section 3 exactly (ASCII digits only, allowed separators: space - ( ) . and a single leading +, 9..15 digits).

No other main file called the changed factories (grep confirmed; `createCashier` has no main caller).

Tests:
- `src/test/java/.../integration/support/ApiTestSupport.java`: `onboardOwner` registration body now sends `"phoneNumber":"081234567890"`.
- `src/test/java/.../unit/service/FreeTierOnboardingServiceTest.java`: 2 calls to `registerWithPassword` got a phone arg.
- `src/test/java/.../unit/service/MerchantBusinessSettingsServiceTest.java`: `createCashier` call got a phone arg.
- NEW `src/test/java/.../unit/shared/PhoneNumberNormalizerTest.java`: loads `docs/contracts/phone-normalization-cases.json` (path `docs/contracts/...` relative to Maven working dir = project root; asserts file exists and >= 16 cases), asserts every case via `tryNormalize` and `normalize` (invalid -> `InvalidOperationException` with message), idempotence of normalized outputs, null input, non-ASCII digits/tab/slash rejected, 9/15 digit boundaries (with and without +). Uses Jackson 3 (`tools.jackson.databind`), same as existing tests.
- NEW `src/test/java/.../unit/service/RegisterServiceTest.java` (Mockito): phone stored normalized (`0812-3456-7890` and `+62 812-3456-7890` -> `6281234567890`), invalid phone rejected with 400-mapped exception and no persistence/encoder/session interaction (and message does not echo the phone), null/blank phone rejected, valid phone + existing email still -> `EmailAlreadyRegisteredException`, Bean Validation of `RegisterUserRequest` (missing/blank/too long phone).
- NEW `src/test/java/.../integration/identity/RegisterPhoneNumberIntegrationTest.java` (extends `AbstractPostgresIntegrationTest`, real HTTP chain via MockMvc): register `0812-3456-7890` stores `6281234567890` and response never contains raw/normalized phone or a `phoneNumber` field; `+62 ...` and `(0812) ...` forms normalize identically; invalid (`0812-345-678a`, `0812`) -> 400, message "Nomor telepon tidak valid.", input not echoed, no user created; missing/blank phone -> 400 with validation error on field `phoneNumber`; same phone allowed for two accounts (no unique); migration check of `information_schema` (nullable, varchar, length 20); legacy row without phone insertable.

## Deviations / decisions
- Normalization runs before the duplicate-email check, so an invalid phone is always 400 regardless of email.
- `tryNormalize` is an extra public method (Optional) used by tests and available for Worker E; `normalize` is the contract entry point.
- Did not edit the Postman collection (`postman/collections/KedaiMbo API/01 - Authentication/Register Account.request.yaml` and `postman/KedaiMbo.postman_collection.json`): outside reserved paths; their register request bodies need a `phoneNumber` field (orchestrator/D decision).

## Orchestrator double-check when compiling
- `tools.jackson.databind.JsonNode#asString()` / `isNull()` / iteration over `JsonNode` (verified in jackson-databind 3.1.5 sources).
- `AuthSessionService.IssuedTokens` public constructor (Lombok `@RequiredArgsConstructor`, 7 args) used in `RegisterServiceTest`.
- Hibernate Validator needs an EL implementation at runtime in `RegisterServiceTest.requestValidationRequiresPhoneNumber` (starter-validation brings tomcat-embed-el normally).
- Integration test assumes register returns HTTP 201/200 (uses `ApiTestSupport.send` tolerant matcher) and 400 for validation/`InvalidOperationException`.
- Worker B owns `AbstractPostgresIntegrationTest`; my integration test only extends it.
- Flyway: the Flyway `R__` demo seed runs only in the `demo` profile; V3 must exist before it (versioned runs first).

## Test status
Written, not run. No commands executed (no Maven).

## Remaining work
None for Section A beyond compile/test run by the orchestrator.

## Follow-up fix (orchestrator report)
`RegisterServiceTest.requestValidationRequiresPhoneNumber` failed because the shared `request(...)` helper pads the email with spaces, which `@Email` rejects. Added `cleanRequest(...)` (unpadded fullName/email) used only by the Bean Validation test; service-level tests keep the padded request. Phone-only violation assertion unchanged and still strict. Not run (no Maven).
