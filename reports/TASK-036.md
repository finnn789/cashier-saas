# TASK-036 worker report — Shared entity package refactor

## Checkpoint: pre-edit (2026-10-08)

- Role: designated implementation worker; canonical `.ai` files are not modified.
- Task baseline: memory version 13; baseline evidence referenced by task at `reports/artifacts/ENTITY-20261008/`.
- Reserved writes: the eight specified entity moves, authorized dependency import/FQCN fixes, and this report only.
- Worktree: pre-existing dirty changes are present across application, migrations, docs, configuration, tests, and task/report files. They are preserved and not attributed to this task.
- Execution constraints: no nested agents, tests, builds, Maven, startup, or database operations will be run.
- Progress: reviewing every current entity and its dependencies before performing the eight approved package moves.

## Classification review (50 current entity sources)

### Moved

- `ProductEntity`, `CategoryEntity`, `OptionGroupEntity`, and `ProductOptionEntity` — moved from `entity.fnb` to `entity.shared.catalog`. They are outlet/tenant-scoped catalog and option models; none depends on F&B order, table, QR, payment, or lifecycle classes.
- `CashierCatalogItemEntity` — moved from `entity.laundry` to `entity.shared.catalog`. It is a tenant/outlet-scoped generic priced catalog item.
- `CashierSaleEntity`, `CashierSaleItemEntity`, and `PickupDeliveryJobEntity` — moved from `entity.laundry` to `entity.shared.sales`. The first two model reusable cashier sale snapshots; pickup/delivery holds generic tenant, outlet, customer, and order UUIDs and has no Laundry entity dependency.

### Retained in F&B

- `DiningTableEntity`, `TableQrTokenEntity` — F&B table/QR interaction model.
- `SalesOrderEntity`, `OrderItemEntity`, `OrderItemOptionEntity`, `FnbOrderStatusHistoryEntity` — F&B order lifecycle and its snapshots.
- `PaymentEntity`, `DiscountEntity`, `FnbGatewayAttemptEntity` — F&B payment, discount, and gateway lifecycle/audit model.

### Retained in Laundry

- `LaundryOrderEntity`, `LaundryOrderItemEntity`, `LaundryOrderStatusHistoryEntity` — Laundry-specific order lifecycle.
- `LaundryServiceCategoryEntity`, `LaundryServiceEntity`, `OutletLaundryServicePriceEntity` — Laundry service and pricing model.

### Retained in shared packages

- Foundation: `BaseEntity`, `MutableBaseEntity` — common persistence lifecycle.
- Audit: `AuditLogEntity`, `OutboxMessageEntity` — cross-domain audit/message infrastructure.
- Billing: `BusinessTypeEntity`, `GatewayEventEntity`, `PlatformInvoiceEntity`, `PlatformPaymentAttemptEntity`, `SubscriptionEntity` — platform billing/business-type concerns.
- Customer: `CustomerEntity`, `CustomerAddressEntity` — cross-domain customer model.
- Identity: `AuthRefreshTokenEntity`, `AuthSessionEntity`, `CashierInvitationEntity`, `MembershipOutletAccessEntity`, `StaffUserEntity`, `StaffUserOutletAccessEntity`, `TenantMembershipEntity` — shared authentication and tenant membership.
- Inventory: `InventoryBalanceEntity`, `InventoryItemEntity`, `InventoryMovementEntity` — generic inventory model.
- Merchant: `OutletEntity`, `TenantEntity` — shared ownership boundary.
- Sales: `OrderEntity`, `CustomerInvoiceEntity`, `CustomerInvoiceLineEntity`, `CustomerPaymentEntity` — shared sales/invoice model.

### Shared-model observations

- The shared catalog entities preserve legacy F&B table names (`products`, `categories`, `option_groups`, `product_options`) but their fields are catalog-generic; no schema or annotation change was made.
- The shared cashier sale entities preserve their `Cashier*` class names and `cashier_*` table names. These are domain-origin naming observations only; no rename, merge, or schema change was authorized.
- Billing, inventory, and platform-payment fields remain appropriately isolated in their existing shared subpackages; no domain-specific shared field required a schema change.

## Checkpoint: implementation complete (2026-10-08)

### Changed files within TASK-036 allowlist

- Moved entities: `ProductEntity`, `CategoryEntity`, `OptionGroupEntity`, `ProductOptionEntity`, and `CashierCatalogItemEntity` to `entity/shared/catalog`; `CashierSaleEntity`, `CashierSaleItemEntity`, and `PickupDeliveryJobEntity` to `entity/shared/sales`.
- Required F&B entity imports: `OrderItemEntity` now imports shared `ProductEntity`; `OrderItemOptionEntity` now imports shared `ProductOptionEntity`.
- Authorized import/FQCN updates only: `CashierMapper`, `FnbCatalogMapper`, `FnbMenuMapper`; `CashierCatalogRepository`, `CashierSaleRepository`, `FnbCatalogRepository`, `MerchantBusinessSettingsRepository`; `CashierCatalogService`, `CashierSaleService`, `FnbCatalogService`, `FnbOrderPricingService`, `FnbPosOrderService`, `FnbTableService`, `PublicOrderService`; `CashierSaleServiceTest`, `FnbMenuMapperTest`, and `PublicOrderServiceTest`.
- `CashierSaleItemEntity` received the explicit import for catalog `CashierCatalogItemEntity` after catalog/sales separation. No other entity body, annotation, table, column, constraint, method, lifecycle, or mapping changed.

### Static evidence

- Reviewed all 50 current entity sources and classified each above.
- Eight destination source files exist; all eight original source paths are absent.
- `rg` over `src/**/*.java` found `STALE_OLD_FQCN_REFERENCES=0`, including all moved entity and generated Q-type package names.
- A normalized comparison against `HEAD` for all eight moved files returned `EXPECTED_CONTENT_MATCH=True`; allowed differences are their package declarations plus the explicit cross-package catalog import in `CashierSaleItemEntity`.
- Verified explicit moved-model imports in both F&B order item entities.
- The worktree already contained extensive unrelated dirty content. It was neither modified intentionally nor attributed to this task.

### Test/build status and limitations

- No tests, Maven/build, startup, database, or nested-agent operations were run, per task instruction.
- Static checks do not replace compilation against regenerated QueryDSL Q-types. The repositories now import the Q types from the moved packages, which is the expected annotation-processor output location.

## Handoff

READY FOR TESTING. Recommended next step, when separately authorized: compile/test the affected repository, catalog, order-pricing, and cashier-sale paths to confirm generated Q-type relocation and runtime entity scanning.
