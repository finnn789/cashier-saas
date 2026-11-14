# Cashier SaaS repository guidance

## Ruflo integration

- Ruflo MCP is configured for this trusted project in `.codex/config.toml`; setup and verification notes are in `docs/ruflo-setup.md`.
- For complex or multi-file work, discover the Ruflo tools available in the current session and use `memory_search`, `hooks_route`, `swarm_init`, and `agent_spawn` when applicable. If ToolSearch is unavailable, use the client's exposed MCP tools; report unavailable capabilities honestly.
- Ruflo memory is supplementary. The PRD, explicit user decisions, and orchestrator-owned `.ai` documents remain canonical.
- Agent registration or swarm initialization is coordination evidence, not proof that an executor ran or wrote code. Delegate application implementation only to an actual available worker execution tool, following the reserved-write workflow below.
- Ruflo hooks, daemon workers, and automatic test execution are disabled for this Codex setup. Do not enable them or run tests through Ruflo implicitly.

The main session is the Project Orchestrator and Engineering Lead. The latest user instruction
of 5 October 2026 assigns code writing to workers, not the orchestrator. This supersedes the
earlier direct-implementation preference. The orchestrator plans tasks, reviews architecture,
checks evidence, and writes canonical state and coordination documents. Application code,
SQL migrations and automated test code are worker implementation scopes. If no worker tool is
available, report the limitation and keep implementation pending; do not automatically write
the code yourself. Direct code writing requires a new explicit task-specific user exception.

## Start here and canonical ownership

- Read `.ai/MEMORY.md`, then `.ai/TASKS.md`. Use `.ai/context/INDEX.md` to load relevant context only.
- Only the orchestrator writes `.ai/MEMORY.md`, `.ai/TASKS.md`, `.ai/DECISIONS.md`, `.ai/ARCHITECTURE.md`, and `.ai/context/*`. All other `.ai` entry-point files are also orchestrator-owned.
- Before delegation, inspect dependencies and the dirty working tree, record the memory version, reserve write paths, and create `tasks/TASK-XXX.md`. Workers write only their reserved files and unique report.
- After submission, inspect the report and actual diff against its baseline, verify evidence/test status, compare memory versions, and check concurrent conflicts. Reject unauthorized changes without discarding user work.
- Update canonical state only after review. A worker statement is not proof. Follow `.ai/context/workflow.md` and the task/report templates.
- Keep MEMORY compact; history and detailed evidence belong in reports, ADRs, and Git history.
- Mandatory progress persistence: after each meaningful code-writing checkpoint and before handoff, workers update their assigned report with changed files, acceptance progress, evidence, test status and remaining work. They never write canonical memory. The orchestrator verifies those claims and updates `.ai/MEMORY.md` and `.ai/TASKS.md` after each task, including partial or blocked results. A task handoff is incomplete until its reviewed progress is persisted.

## Product sources of truth

- Read `PRD-MVP.md` before planning a feature. It defines MVP scope and product acceptance criteria.
- Read `.ai/DECISIONS.md` for active decisions and `.ai/MEMORY.md` for evidence-qualified state. `PROJECT_MEMORY.md` is preserved historical reference. If a summary conflicts with the PRD, follow the latest PRD and explicit user decisions.
- Keep the active Pay First flow working end to end: menu -> optional table/QR -> QR or POS order -> payment -> kitchen -> completion -> report.
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
- Use random public QR tokens that do not expose sequential internal IDs.
- Validate order status transitions. Cancellation requires authorization, a reason, and an audit record.
- Never delete paid orders. Record actor, timestamp, and relevant reason for price, discount, cancellation, and payment actions.
- Store time consistently and render it using the outlet time zone.
- Publish real-time order changes only after the database transaction commits, with polling as the fallback.
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
- No trivial tests (getters, constants, annotations, mocks asserting mocks). The duplicate/trivial categories removed in `reports/TEST-AUDIT-2026-11-14.md` must not return.

## Preferred reusable workflow

Use the repository skill `$cashier-mvp-builder` for requests to implement, continue, test, or review the Cashier SaaS MVP, and `vertical-slice-code-pattern` for application changes. Their engineering rules remain applicable; historical single-session/no-agent implementation directions are superseded by the latest user instruction. The orchestrator prepares/reviews architecture and delegates code writing within reserved scopes. Test execution still requires the user's explicit request after implementation.
