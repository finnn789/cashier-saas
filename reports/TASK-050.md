# TASK-050 Worker Report: Elimination of Legacy Redundant Cashier Tables & Clean Architecture Standardization

## Checkpoint 0 — Baseline & Scope Acknowledgment

- **Memory Version**: 38
- **Task Reference**: `tasks/TASK-050.md`
- **Objective**: Execute the removal of redundant legacy cashier tables (`cashier_catalog_items`, `cashier_sales`, `cashier_sale_items`) and obsolete legacy classes to standardize all catalog and checkout operations exclusively on the canonical F&B POS model (`ProductEntity` / `public.products`, `SalesOrderEntity` / `public.sales_orders`, `OrderItemEntity` / `public.order_items`, `PaymentEntity` / `public.payments`).
- **Scope of Changes**:
  - Flyway migration `V2__drop_legacy_cashier_tables.sql` dropping `cashier_sale_items`, `cashier_sales`, and `cashier_catalog_items`.
  - Update `MerchantBusinessSettingsRepository` removing `CashierSaleEntity` reference and verifying currency usage solely via `SalesOrderEntity`.
  - Clean `CashierMapper` retaining only `toOutletResponse(...)` and removing all methods/imports referencing legacy entities/DTOs.
  - Delete legacy entities: `CashierCatalogItemEntity`, `CashierSaleEntity`, `CashierSaleItemEntity`.
  - Delete legacy repositories: `CashierCatalogRepository`, `CashierSaleRepository`.
  - Delete legacy services & controllers: `CashierCatalogService`, `CashierSaleService`, `CashierCatalogController`, `CashierSaleController`.
  - Delete obsolete DTOs: `CashierCatalogItemRequest`, `CashierPriceChangeRequest`, `CashierSaleRequest`, `CashierSaleLineRequest`, `CashierCatalogItemResponse`, `CashierSaleResponse`, `CashierSaleLineResponse`.
  - Delete obsolete unit test: `CashierSaleServiceTest`.
  - Clean compile verification: `./mvnw.cmd test-compile`.

---

## Checkpoint 1 — Implementation Details

### 1. Database Migration
- Created `src/main/resources/db/migration/V2__drop_legacy_cashier_tables.sql`:
  ```sql
  DROP TABLE IF EXISTS public.cashier_sale_items CASCADE;
  DROP TABLE IF EXISTS public.cashier_sales CASCADE;
  DROP TABLE IF EXISTS public.cashier_catalog_items CASCADE;
  ```
- Drops legacy flat tables safely with `CASCADE` if foreign key constraints exist.

### 2. Repository Updates
- Updated `src/main/java/org/lite/project/cashiersaas/repository/MerchantBusinessSettingsRepository.java`:
  - Removed import `org.lite.project.cashiersaas.entity.shared.sales.CashierSaleEntity`.
  - Streamlined `hasCurrencyUse(UUID tenantId)` to check exclusively `exists(SalesOrderEntity.class, "salesOrder", tenantId)`.

### 3. Mapper Cleanup
- Updated `src/main/java/org/lite/project/cashiersaas/dto/CashierMapper.java`:
  - Retained `toOutletResponse(OutletEntity outlet)`.
  - Removed `toCatalogResponse`, `toSaleResponse`, and `toLineResponse`.
  - Cleaned up all obsolete imports.

### 4. Legacy File Removals
- **Entities Deleted (3 files)**:
  - `src/main/java/org/lite/project/cashiersaas/entity/shared/catalog/CashierCatalogItemEntity.java`
  - `src/main/java/org/lite/project/cashiersaas/entity/shared/sales/CashierSaleEntity.java`
  - `src/main/java/org/lite/project/cashiersaas/entity/shared/sales/CashierSaleItemEntity.java`
- **Repositories Deleted (2 files)**:
  - `src/main/java/org/lite/project/cashiersaas/repository/CashierCatalogRepository.java`
  - `src/main/java/org/lite/project/cashiersaas/repository/CashierSaleRepository.java`
- **Services & Controllers Deleted (4 files)**:
  - `src/main/java/org/lite/project/cashiersaas/service/CashierCatalogService.java`
  - `src/main/java/org/lite/project/cashiersaas/service/CashierSaleService.java`
  - `src/main/java/org/lite/project/cashiersaas/controller/CashierCatalogController.java`
  - `src/main/java/org/lite/project/cashiersaas/controller/CashierSaleController.java`
- **DTOs Deleted (7 files)**:
  - `src/main/java/org/lite/project/cashiersaas/dto/request/CashierCatalogItemRequest.java`
  - `src/main/java/org/lite/project/cashiersaas/dto/request/CashierPriceChangeRequest.java`
  - `src/main/java/org/lite/project/cashiersaas/dto/request/CashierSaleRequest.java`
  - `src/main/java/org/lite/project/cashiersaas/dto/request/CashierSaleLineRequest.java`
  - `src/main/java/org/lite/project/cashiersaas/dto/response/CashierCatalogItemResponse.java`
  - `src/main/java/org/lite/project/cashiersaas/dto/response/CashierSaleResponse.java`
  - `src/main/java/org/lite/project/cashiersaas/dto/response/CashierSaleLineResponse.java`
- **Unit Tests Deleted (1 file)**:
  - `src/test/java/org/lite/project/cashiersaas/unit/service/CashierSaleServiceTest.java`

---

## Checkpoint 2 — Compilation Verification & Invariants

1. **Compilation Verification**:
   - Executed: `./mvnw.cmd test-compile`
   - Result: `BUILD SUCCESS` (Java 17, 263 source files, 25 test source files compiled with 0 errors).
2. **Testing Policy Compliance**:
   - Testing not run by repository policy per `AGENTS.md`. No unit, integration, or API tests were executed.
3. **Domain & Architectural Invariants**:
   - Canonical F&B POS architecture (`ProductEntity`, `SalesOrderEntity`, `OrderItemEntity`, `PaymentEntity`) remains the single source of truth for catalog and checkout.
   - Preserved all active endpoints (`FnbCatalogController`, `FnbStaffOrderController`, `FnbPaymentController`, `CashierGrpcService`).
   - Tenant isolation, role-based authorization, and monetary invariants are untouched and intact.

---

## Status

**READY FOR TESTING** (testing not run by repository policy).
