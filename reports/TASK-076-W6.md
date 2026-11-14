# TASK-076 W6 report (Postman + docs)

Status: written, nothing run (no git/maven/postman execution).

## Changed files
- `postman/collections/KedaiMbo API/04 - Cashier Invitations/`: new `List Outlet Staff` (GET .../staff, order 3000), `Deactivate Staff` (4000), `Reactivate Staff` (5000), `Reset Staff Password` (6000), all `{{baseUrl}}/api/v1/merchant/outlets/{{outletId}}/staff[/{{staffUserId}}/...]`, inheriting the collection bearer auth like the Issue Cashier Invitation request. No new folder was needed.
- `postman/collections/KedaiMbo API/01 - Authentication/Change Password.request.yaml` (new, order 8000): `POST /api/v1/auth/password`, body `currentPassword={{password}}`, `newPassword={{newPassword}}`, inherited bearer auth.
- `postman/collections/KedaiMbo API/.resources/definition.yaml`: variables `staffUserId` and `newPassword` (secret, empty); description paragraph covering staff management, Change Password, the mock gateway flag and the cashier-cannot-cancel-discounted-order rule.
- `docs/architecture.md`: service list, `POST /auth/password` in the security table, new "Staff management and passwords" subsection, `FNB_ORDER_CREATED` in checkout step 6, mock flag in the Payment section, cashier cancel rule in the lifecycle section, gRPC table (`user_id`, `price_amount`/`total` decimal strings) plus paragraph on money strings and user/outlet scoping, adapter injects `user_id`.
- `docs/fnb-operations.md`: mock gateway disabled by default (demo profile enables it; initiate rejected, resolve 404), cashier cannot cancel a discounted order, `FNB_ORDER_CREATED` audit.
- `docs/demo.md`: note that the mock gateway is on in the demo profile.
- `README.md`: feature list for staff management/self password change, checkout audit, cancel rule, mock flag (default off), gRPC decimal strings + `user_id`, and the limitations line about the mock gateway.
- `Ai-services/README.md`: removed `CpuUsageTool` bullet, the CPU monitoring example and the `CpuUsageTool` mention in the test list; proto section now says the source is `../src/main/proto/cashier_service.proto` and `proto/cashier_service.proto` must be identical (copy before compiling); command unchanged.
- `Ai-services/GEMINI.md`: tool list now `BaseTool`, `ToolRegistry`, `CatalogSearchTool`, `OrderStatusTool` (CPU removed).
- `Ai-services/proto/cashier_service.proto`: replaced with a byte-identical copy of `src/main/proto/cashier_service.proto` (verified with `cmp`).

## Notes / deviations
- The YAML collection has no mock payment requests (no payment requests at all), so the mock-flag note went into the collection description instead of per-request notes; I did not invent payment requests (out of scope).
- Facts documented were checked against the current source diff vs `038a2f7` (routes, messages, gRPC scoping query, cancel/mock/audit code). `.env.example` and `docker-compose.yml` already contain `CASHIER_MOCK_GATEWAY_ENABLED` (W2).
- Docs state `ACTIVE`/`DISABLED` sync and the 409 on password reset for cashiers with another active tenant as per the W1 report and code contract; temporary-password length (12) from the contract.
- CRLF/LF line endings preserved per file.
