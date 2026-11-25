# Cashier SaaS repository guidance

The main session is the orchestrator: it plans tasks, reviews reports and diffs, and writes the canonical docs (`.ai/*`, `tasks/`). Application code, SQL migrations and test code are written by workers within reserved paths. If no worker tool is available, report it and keep implementation pending unless the user gives a task-specific exception.

## Start here and canonical ownership

- Read `.ai/MEMORY.md`, then `.ai/TASKS.md`. Use `.ai/context/INDEX.md` to load relevant context only.
- Only the orchestrator writes `.ai/MEMORY.md`, `.ai/TASKS.md`, `.ai/DECISIONS.md`, `.ai/ARCHITECTURE.md`, and `.ai/context/*`. All other `.ai` entry-point files are also orchestrator-owned.
- Before delegation, inspect dependencies and the dirty working tree, record the memory version, reserve write paths, and create `tasks/TASK-XXX.md`. Workers write only their reserved files and unique report.
- After submission, inspect the report and actual diff against its baseline, verify evidence/test status, compare memory versions, and check concurrent conflicts. Reject unauthorized changes without discarding user work.
- Update canonical state only after review. A worker statement is not proof. Use `tasks/_TEMPLATE.md` for task packets.
- Keep MEMORY compact; history and detailed evidence belong in reports, ADRs, and Git history.
- Mandatory progress persistence: after each meaningful code-writing checkpoint and before handoff, workers update their assigned report with changed files, acceptance progress, evidence, test status and remaining work. They never write canonical memory. The orchestrator verifies those claims and updates `.ai/MEMORY.md` and `.ai/TASKS.md` after each task, including partial or blocked results. A task handoff is incomplete until its reviewed progress is persisted.

## Product sources of truth

- Read `PRD-MVP.md` before planning a feature. It defines MVP scope and product acceptance criteria.
- Read `.ai/DECISIONS.md` for active decisions and `.ai/MEMORY.md` for evidence-qualified state. If a summary conflicts with the PRD, follow the latest PRD and explicit user decisions.
- Product flow and scope are defined by `PRD-MVP.md` v3 (section 0 is binding). QR ordering, kitchen display, tables and WebSocket were removed in the pivot.
- Do not add post-MVP features unless the user explicitly changes the scope.

## Technical baseline

- Use Java 17, Spring Boot 4.1.1, Spring MVC, Spring Security, and PostgreSQL unless the user approves a stack change.
- Use the Maven wrapper (`mvnw.cmd` on Windows) for build and test commands.
- Prefer cohesive vertical slices over creating many unused abstractions in advance.
- Keep API, persistence, authorization, validation, and tests aligned within each implemented slice.
- Do not run unit, API, integration, or Maven test commands while writing a feature. Run tests only when the user explicitly requests testing after the feature is complete end to end. Until then, report testing as not run by policy.

## DTO and code design

- Every API DTO must be a named `public class`; never use Java `record` for request or response DTOs.
- Use Lombok `@Getter` and `@Setter` on DTOs. Add constructors or `@Builder` only when the actual use case requires them; do not use `@Data` by default.
- Keep DTOs in the existing top-level `dto/request` or `dto/response` packages, using an existing domain subpackage such as `identity` when appropriate. Never expose persistence entities directly through controllers.
- Return controller payloads directly as `ApiResponse<T>` using its `success`, `error`, or `validationError` factory methods. Do not use `ResponseEntity` for API bodies. `GlobalExceptionHandler` owns error HTTP mapping and uses `@ResponseStatus` for error status codes. Keep runtime exceptions in `shared/exception/runtime` and do not create feature-specific HTTP exception handlers or automatic response-body wrappers.
- Follow the existing top-level package layout: `controller`, `service`, `repository`, `dto`, and the established entity/shared packages. Put each new class in the matching existing package; do not create feature root packages such as `membership`. Controllers adapt HTTP, services own use cases and transaction boundaries, repositories own persistence, and explicit mappers translate entities and DTOs.
- Use Strategy plus a narrow factory only when an actual variation exists, such as payment methods or pricing rules. Use policy/guard classes for reusable authorization and tenant-access checks.
- Prefer composition and focused services over generic `BaseService`, `AbstractCrudService`, utility catch-alls, or premature interfaces. Keep business transitions as named domain/service methods rather than public mutable setters.
- Do not add `@Deprecated` methods or compatibility overloads for unused APIs. Update callers to the current contract and remove obsolete methods instead, unless the user explicitly requests a compatibility period.
- Do not introduce wildcard generic types (`<?>`, `? extends`, or `? super`) in application code. Use a concrete type at each application boundary. If an external framework makes a wildcard unavoidable, isolate it in the smallest adapter/configuration boundary and explicitly document why before introducing it.

## Authentication and authorization

- The active security role model is exactly `SUPERADMIN`, `OWNERMERCHANT`, and `CASHIER`. Do not add or rename roles without an explicit product instruction.
- Use stateless Bearer JWT authentication. Passwords use BCrypt; token issuance belongs to `JwtTokenService`, while credential verification belongs to a future `AuthenticationService` backed by identity persistence.
- JWTs must contain validated issuer, audience, expiry, token ID, user subject, tenant ID, and a roles array. Never log tokens or secrets.
- JWT HMAC secrets are supplied only through `CASHIER_JWT_SECRET_B64`; no production default secret may be committed.
- Tenant identity comes from the validated JWT principal, never from request payload or a client-chosen header.
- Keep responsibilities separate: `SecurityConfiguration` owns HTTP security policy and password encoding; `JwtConfiguration` owns JWT properties, signing key, encoder, decoder, validators, and authentication converter; `JwtTokenService` owns token issuance only.

## Domain invariants

- Enforce tenant isolation in every operational read and write; derive tenant context from the authenticated principal, never from a request body, and return no evidence that another tenant's record exists.
- Calculate totals on the server and store product names and prices as order snapshots.
- Represent money with `BigDecimal` or integer minor units, never floating point.
- Require an idempotency key for checkout and validate product availability again during checkout.
- Validate order status transitions. Cancellation requires authorization, a reason, and an audit record.
- Never delete paid orders. Record actor, timestamp, and relevant reason for price, discount, cancellation, and payment actions.
- Store time consistently and render it using the outlet time zone.
- Build sales reports from paid, non-cancelled transactions only.

## Delivery workflow

- When a task is unspecified, select the smallest incomplete slice from the earliest incomplete PRD milestone.
- State the selected acceptance criteria and assumptions before editing. Make routine reversible assumptions; ask only when an unresolved choice changes public behavior, security, or the data model materially.
- Decompose project work into bounded tasks and assign code implementation to workers. Parallel tasks must have disjoint reserved writes and compatible reviewed contracts. The main session performs orchestration, read-only review and canonical updates.
- Use Codebase Memory MCP for structural context and verify findings against current source. Fresh workers receive explicit context and task contracts; they do not inherit conversation history.
- Preserve unrelated user changes and avoid broad rewrites.
- Do not automatically run tests after implementation. When the end-to-end feature is ready, hand it back as `READY FOR TESTING` with the recommended unit/API scenarios. Execute tests yourself only after the user explicitly requests testing.
- A feature implementation is ready for testing when its acceptance path is implemented, authorization and tenant boundaries are reviewed, and the result is summarized with remaining risks. Test completion additionally requires the user-requested relevant tests to pass.

## Unit test writing rule (user, 2026-11-14)

- Tests must not add decoupling layers: no new test base classes, helpers, builders, factories, fake adapters or wrapper interfaces. Production code is never changed only to make it testable (no extra interfaces, constructor parameters or visibility changes).
- If a behaviour is already covered by an existing unit or integration test, do not add another one. Extend the existing test class instead of creating a new file, use the existing fixtures, write one test per behaviour, and parameterize variants.
- No trivial tests (getters, constants, annotations, mocks asserting mocks) and do not reintroduce duplicate/trivial categories.

## Task lifecycle (user, 2026-11-24)

- A task has three artifacts: the packet `tasks/TASK-N.md` (the contract; written by the orchestrator BEFORE delegation), worker reports `reports/TASK-N-<id>.md` (written by each worker AFTER its work; Python workers use `Ai-services/reports/`), and one ledger line in `.ai/TASKS.md`.
- Only the orchestrator closes a task. DONE means: diff reviewed against the baseline, compiled (Java `mvnw.cmd compile`; UI `tsc -b` + `oxlint`), user-requested tests passed, and for repo-tracked work committed with the user's approval. The UI repo is not git, so its tasks are DONE after review, compile and the user's go-ahead.
- On DONE the orchestrator deletes `tasks/TASK-N.md` and every `reports/TASK-N-*.md`, then leaves exactly one line under "Done" in `.ai/TASKS.md` (what shipped, commit hash or "UI repo, not git", open debt). Contracts, debt or decisions worth keeping move into that line, `.ai/DECISIONS.md` or `.ai/ARCHITECTURE.md` BEFORE the deletion.
- Never delete the packet or reports of a task that is not DONE. Untracked files cannot be recovered from git, so confirm DONE first.
- Workers never create, edit or delete anything in `tasks/` or `reports/` except their own report file named in the packet, and never delete files outside their reserved paths. Deleting housekeeping files is done by the orchestrator or by `tidy-worker` with an explicit file list.
- A packet is self-contained: it copies the verified facts it needs and does not point to other tasks' packets or reports, which may already be deleted. Number = next free number. Required sections: user instruction, verified facts, design decisions, reserved paths per worker, acceptance.
- Compile checks are run by the orchestrator only; tests (Maven, pytest, vitest, Postman) run only when the user asks.