# Cashier SaaS repository guidance

For this project, work directly in the main Codex session. This project-specific rule takes precedence over the global Ruflo and multi-agent workflow.

## Product sources of truth

- Read `PRD-MVP.md` before planning a feature. It defines MVP scope and product acceptance criteria.
- Read `PROJECT_MEMORY.md` for stable decisions and domain language. If it conflicts with the PRD, follow the latest PRD.
- Keep the mandatory product flow working end to end: menu -> table/QR -> QR or POS order -> kitchen -> payment -> report.
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
- Work directly on project tasks, including exploration, implementation, review, and user-requested testing. Do not spawn Ruflo or Codex agents for this repository.
- Use available read-only tools for codebase context when useful, without routing work to agents.
- Preserve unrelated user changes and avoid broad rewrites.
- Do not automatically run tests after implementation. When the end-to-end feature is ready, hand it back as `READY FOR TESTING` with the recommended unit/API scenarios. Execute tests yourself only after the user explicitly requests testing.
- A feature implementation is ready for testing when its acceptance path is implemented, authorization and tenant boundaries are reviewed, and the result is summarized with remaining risks. Test completion additionally requires the user-requested relevant tests to pass.

## Preferred reusable workflow

Use the repository skill `$cashier-mvp-builder` for requests to implement, continue, test, or review the Cashier SaaS MVP. Handle the full workflow directly in this session. For new features or changes to schema, API contracts, or cross-cutting security, prepare and review the architecture yourself before editing.
