# TASK-050: Elimination of Legacy Redundant Cashier Tables & Clean Architecture Standardization

Status: IN PROGRESS / DELEGATED  
Memory version: 38  

---

## 1. Context & User Directive
Per explicit user decision on 2026-10-27: The legacy flat cashier tables (`cashier_catalog_items`, `cashier_sales`, `cashier_sale_items`) and their associated redundant controllers/services are to be cleanly removed from the database and codebase. All product catalog and checkout operations are standardized strictly on the canonical F&B POS architecture (`ProductEntity` / `public.products`, `SalesOrderEntity` / `public.sales_orders`, `OrderItemEntity` / `public.order_items`, `PaymentEntity` / `public.payments`).

---

## 2. Scope & Phase Breakdown

### Phase 1: Database Migration & Entity/Repository Removal
1. **Flyway Migration (`src/main/resources/db/migration/V2__drop_legacy_cashier_tables.sql`)**:
   ```sql
   DROP TABLE IF EXISTS public.cashier_sale_items CASCADE;
   DROP TABLE IF EXISTS public.cashier_sales CASCADE;
   DROP TABLE IF EXISTS public.cashier_catalog_items CASCADE;
   ```
2. **Remove Legacy Entities**:
   - Delete `src/main/java/org/lite/project/cashiersaas/entity/shared/catalog/CashierCatalogItemEntity.java`
   - Delete `src/main/java/org/lite/project/cashiersaas/entity/shared/sales/CashierSaleEntity.java`
   - Delete `src/main/java/org/lite/project/cashiersaas/entity/shared/sales/CashierSaleItemEntity.java`
3. **Update Repository References**:
   - In `src/main/java/org/lite/project/cashiersaas/repository/MerchantBusinessSettingsRepository.java`: Remove import and check of `CashierSaleEntity` (only verify `SalesOrderEntity.class, "salesOrder", tenantId`).
4. **Remove Legacy Repositories**:
   - Delete `src/main/java/org/lite/project/cashiersaas/repository/CashierCatalogRepository.java`
   - Delete `src/main/java/org/lite/project/cashiersaas/repository/CashierSaleRepository.java`

### Phase 2: Controller, Service, DTO & Test Cleanup
1. **Remove Legacy Services & Controllers**:
   - Delete `src/main/java/org/lite/project/cashiersaas/controller/CashierCatalogController.java`
   - Delete `src/main/java/org/lite/project/cashiersaas/controller/CashierSaleController.java`
   - Delete `src/main/java/org/lite/project/cashiersaas/service/CashierCatalogService.java`
   - Delete `src/main/java/org/lite/project/cashiersaas/service/CashierSaleService.java`
2. **Clean DTOs & Mappers**:
   - Delete obsolete DTOs:
     - `src/main/java/org/lite/project/cashiersaas/dto/request/CashierCatalogItemRequest.java`
     - `src/main/java/org/lite/project/cashiersaas/dto/request/CashierPriceChangeRequest.java`
     - `src/main/java/org/lite/project/cashiersaas/dto/request/CashierSaleRequest.java`
     - `src/main/java/org/lite/project/cashiersaas/dto/request/CashierSaleLineRequest.java`
     - `src/main/java/org/lite/project/cashiersaas/dto/response/CashierCatalogItemResponse.java`
     - `src/main/java/org/lite/project/cashiersaas/dto/response/CashierSaleResponse.java`
     - `src/main/java/org/lite/project/cashiersaas/dto/response/CashierSaleLineResponse.java`
   - In `src/main/java/org/lite/project/cashiersaas/dto/CashierMapper.java`: Remove methods referencing `CashierCatalogItemEntity`, `CashierSaleEntity`, `CashierSaleItemEntity` (retain only `toOutletResponse`).
3. **Remove Obsolete Test**:
   - Delete `src/test/java/org/lite/project/cashiersaas/unit/service/CashierSaleServiceTest.java`
4. **Compile Verification**:
   - Verify that the Maven test-compile succeeds cleanly: `./mvnw.cmd test-compile` (Release 17, zero compile errors).

---

## 3. Invariants & Rules
- Do NOT touch `FnbCatalogController`, `FnbStaffOrderController`, `FnbPaymentController`, `CashierGrpcService`, or any security/auth files.
- Tests are NOT run by policy (`AGENTS.md`), only compilation check (`./mvnw.cmd test-compile`).
- Persist report at `reports/TASK-050.md` upon completion.
