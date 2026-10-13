# F&B backend — stage 1: QR context and product contract

Current status (2026-10-06): NEEDS_REVISION. TASK-003's QR-only V11 conflicts with the
different V11 already recorded in the database. See the recovery boundary below; stage-1
code presence and the REST contract do not establish deployment or runtime readiness.

## Delivery boundary

This change implements stage 1 only. Payment recording, POS creation, shared pricing extraction,
kitchen transitions, discounts, WebSocket, reports and CSV remain subsequent stages. No frontend changes.

The approved product contract is Pay First: NEW/UNPAID -> payment recorded atomically ->
CONFIRMED/PAID -> PREPARING -> READY -> COMPLETED. Owner controls discounts before tax/service
charge. Owner/Cashier may cancel only unpaid orders, with a reason/audit. These operational
transitions are a contract for subsequent stages, not a claim that stage 1 implements them.

## Implemented QR slice

- OUTLET QR has no dining table. TABLE QR must have one.
- A merchant can operate the public menu and existing QR checkout without creating any table.
- Keep the existing `TableQrTokenEntity` / `table_qr_tokens` storage name to avoid an unrelated
  rename; `qrType` distinguishes the contexts. Existing token values/public URLs remain valid.
- One outlet QR row per tenant/outlet, backed by a partial unique index. Repeated creation
  returns the same token. Rotation changes that row's token and invalidates the previous one.
- Owner-only management via existing HTTP role rules and MerchantAccessGuard. Tenant comes
  from the authenticated principal; outlet is checked against authorized membership/access.
- QR operations serialize with existing checkout through the tenant lock. QR, outlet and
  optional table are refreshed after waiting for the lock to avoid accepting rotated tokens.
- Public context is derived from the random QR token, not client-supplied tenant/table IDs.
- Public menu returns qrType and serviceTypes. tableName is omitted for OUTLET QR.
- OUTLET supports DINE_IN without a table and TAKEAWAY. TABLE supports DINE_IN only.
- Existing QR checkout is adapted only as needed for nullable table context: no synthetic
  table, no null dereference, serviceType validation, and cross-outlet idempotency conflict.
  Omitting serviceType retains the existing DINE_IN default. Explicit null is rejected.
- Order remains NEW/UNPAID. Existing table activity behavior is retained in this stage;
  automatic payment-aware table reconciliation belongs to the payment/lifecycle stage.

## REST contract

Prefix: `/api/v1/merchant/outlets/{outletId}/fnb/outlet-qr`

- GET: read QR; 404 if not created, without writing.
- PUT: create or return the existing outlet QR; no request body.
- POST `/rotate`: rotate an existing outlet QR; 404 if not created.

Responses: `ApiResponse<FnbQrResponse>` with qrType, token and menuPath.
menuPath is a backend API path, not a frontend URL or a printable QR image.

Existing public routes remain:
- GET `/api/v1/public/fnb/menu/{qrToken}`
- POST `/api/v1/public/fnb/menu/{qrToken}/orders` with Idempotency-Key
- GET `/api/v1/public/fnb/orders/{orderToken}/status`

Checkout retains its existing fields (customerLabel, items) and adds optional serviceType
(default DINE_IN). A table QR rejects TAKEAWAY. Reusing an idempotency key with a different
service type or outlet is rejected; existing table-order fingerprints remain compatible.

## Migration and verification boundaries

TASK-003 wrote `V11__fnb_outlet_qr_context.sql` for TASK-002's observed source/schema gap.
That migration assignment is now NEEDS_REVISION, because [TASK-004](../reports/TASK-004.md)
confirmed a different already-applied V11. The QR-only replacement contains these changes:

- Adds `qr_type` as VARCHAR(16), backfills legacy table rows to TABLE and sets TABLE as default.
- Requires a non-null type and exactly TABLE/non-null-table or OUTLET/null-table context.
- Makes the table link nullable while retaining existing table/token uniqueness and table FKs.
- Adds direct `(tenant_id, outlet_id)` ownership validation against outlets; the table FK alone
  cannot provide this check when the table is null.
- Adds a partial unique index for one OUTLET row per tenant/outlet, including inactive rows.

The migration takes an exclusive QR-table lock and relies on Flyway's normal PostgreSQL
transaction so invalid data rolls the migration back. Existing row IDs and token values are
preserved. Order/payment/discount/report/event/history schemas are outside this change;
`sales_orders.dining_table_id` already permits NULL.

Supported starting state is the schema produced by V1–V10. Limited Hibernate drift is supported:
`qr_type` may already be an ordinary VARCHAR(16) column, and the table link may already be
nullable. Existing explicit type values are preserved; null types are backfilled only for rows
that have a table. NOT NULL, context, FK and uniqueness checks validate all existing rows.
Incompatible column definitions, ambiguous rows with no type/table, invalid context, duplicate
OUTLET rows or cross-scope references fail instead of deleting or relabeling data. New constraint
or index name collisions also fail; their definitions are not blindly trusted. This is not a
general repair for other V1–V10 schema drift or an already-applied draft V11.

No migration, application startup, compilation or automated tests have been run for TASK-003.
Read-only database inspection on 2026-10-06 confirms successful V11
`V11__fnb_pay_first_operations.sql`, checksum `835635316`. Current source/build contains
the different QR-only V11, previously resolved as checksum `866145998`; its source SHA-256
is unchanged. The database already has `ck_qr_context`, `fk_qr_outlet_scope`, `uk_qr_outlet`,
required VARCHAR(16) type and nullable table link. Renaming the replacement V12 would also
attempt to recreate the existing outlet FK. The original applied SQL remains unrecovered.

[TASK-005](../tasks/TASK-005.md) freezes the worker recovery contract: recover authentic
content with provenance and matching Flyway checksum, preserve the replacement outside
migration discovery, restore exactly one original V11, then report any actual remaining delta.
Do not rewrite applied history or run repair to accept the replacement. No later migration is
assigned. Clean build-output reconstruction and startup/schema/API verification remain future
authorized work; exclude stale V11 artifacts before any such startup.

## Recommended tests (execute only on user request)

1. Owner creates/reads/rotates an outlet QR without a dining table; repeated PUT is stable.
2. Cashier cannot manage QR; another tenant/outlet sees no evidence of a QR.
3. Public OUTLET menu omits tableName and exposes both service types; TABLE exposes DINE_IN.
4. Checkout without a table supports both types and stays NEW/UNPAID; TABLE rejects TAKEAWAY.
5. Same checkout replay returns the existing order; changed type/outlet conflicts.
6. Old QR fails after rotation, including a checkout waiting on the tenant lock.
7. Inactive tenant/outlet/table/subscription is rejected appropriately.
8. Migration preserves existing QR values, permits OUTLET with NULL table, rejects invalid
   context pairs and cross-scope outlets/tables, and rejects duplicate outlet QR rows even if
   inactive. Duplicate table links and tokens remain rejected.
9. Migration accepts the documented compatible VARCHAR(16) column drift, preserves valid
   explicit OUTLET rows and rejects incompatible column types, unknown/null context, ambiguous
   no-type/no-table rows and new constraint/index name collisions without partial changes.

TASK-003 status: NEEDS_REVISION; earlier READY FOR TESTING was withdrawn after applied-history
inspection. Migration/database/API behavior remains unverified at runtime. The recommended
checks above require a corrected source baseline and explicit user testing authorization.
