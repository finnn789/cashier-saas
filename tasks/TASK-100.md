# TASK-100: Ingredient purchases, manual entry (PRD v3 slice 3a, FR-IN-02)

Status: J1+F1 DONE, REVIEWED, READY FOR TESTING, backend NOT COMMITTED (2026-11-25, memory v99).

## User instruction (2026-11-25)
"Lanjutkan ke feature berikutnya, silahkan delegasikan ke agent." Next PRD v3 slice is (3) purchases + receipt OCR. The OCR half needs open decisions (object storage, vision model), so this task delivers the decision-free half: a manual purchase document. OCR (FR-AI-01) will later create a DRAFT of exactly this document.

## Verified facts (orchestrator, source read)
- PRD: FR-IN-02 "Pembelian bahan (manual atau dari OCR) -> menambah stok + mencatat harga beli per satuan"; tables `purchases`, `purchase_lines` (§9); role table: purchases for Owner and Manager (own outlet), not Cashier. No MANAGER role exists, so **OWNER only** (`/merchant/**`).
- Stock today: `InventoryMovementEntity` (`entity/shared/inventory`), `Type { RESTOCK, WASTE, ADJUSTMENT, SALE, SALE_REVERSAL }`, columns `order_id`, `reversal_of_movement_id`, `unit_cost` numeric(19,4), `idempotency_key` varchar(128), `request_fingerprint` varchar(64); factory `record(tenantId, outletId, item, orderId, type, delta, reason, key, fingerprint, actorMembershipId, reversalOfMovementId, unitCost)`.
- `InventoryBalanceEntity.receive(quantity, unitCost)` updates the moving-average cost (resets when stock <= 0), then quantity. `FnbInventoryService.recordMovement` (RESTOCK path) shows the full pattern: lock the balance, receive, persist the movement, flush, margin warnings via `FnbMenuMarginService.restockWarnings(tenantId, outletId, itemId, tenant.getTargetMarginPct())` (returns `List<String>`) when the average changed, and (TASK-097) an audit row.
- Header + lines pattern to mirror: `V13__stock_counts_and_leakage.sql` (`stock_counts` with `uk_..._tenant_key UNIQUE (tenant_id, idempotency_key)`, lines with `movement_id` FK), `StockCountEntity` / `StockCountLineEntity`, `FnbInventoryService.stockCount` (sorted lines by item id so balance locks are taken in a fixed order; idempotent replay returns the first result; fingerprint mismatch -> 409 "Kunci idempotensi telah digunakan."), `FnbStockCountRequest` (`idempotencyKey` in the BODY, `@Size(max=128)`), list endpoint `GET .../fnb/inventory/stock-counts?page&size` (`FnbLeakageService.stockCounts`).
- Next Flyway version: **V16** (V13-V15 exist).
- Audit: `AuditLogEntity.Action` enum + `recordAction(...)`; column `action` varchar(80), no CHECK; `AuditLogRepository audit` is already injected in `FnbInventoryService` (TASK-097).
- Leakage report counts RESTOCK movements as stock in, so purchases via RESTOCK movements need no leakage change.

## Contract (FINAL; both workers build to it)
All under `/api/v1/merchant/outlets/{outletId}/fnb/inventory/purchases`, OWNER (`guard.requireOwner(principal, outletId)`), tenant from the principal; another tenant's/outlet's purchase or item -> 404.

`POST` body `FnbPurchaseRequest`:
```
idempotencyKey: string, required, <= 128
supplierName: string?, <= 120 (trimmed; blank -> null)
purchasedOn: "YYYY-MM-DD", required, not after today in the tenant time zone (400 "Tanggal pembelian tidak boleh di masa depan.")
note: string?, <= 500
lines: 1..50 of FnbPurchaseLineRequest {
  inventoryItemId: UUID, required (active item of this outlet, else 404)
  quantity: decimal > 0, Digits(11,3)
  unitCost: decimal >= 0, required, Digits(15,4)   // price per item unit
}
duplicate inventoryItemId -> 400 "Bahan duplikat dalam pembelian."
```
Effect, one transaction: lock balances in item-id order; per line `balance.receive(quantity, unitCost)` + a `RESTOCK` movement (unitCost set, reason "Pembelian" or "Pembelian: <supplier>", idempotency key `purchase:<key>:<itemId>`, same fingerprint); persist header + lines (`movement_id` set); one audit row `FNB_PURCHASE_RECORDED("PURCHASE")` (entityId = purchase id, reason = note, details `supplier=<name or -> lines=<n> total=<plain>`); same key + same fingerprint -> replay returns the first response with NO new rows (200); same key + different payload -> 409.
`lineTotal = quantity * unitCost` rounded HALF_UP to 2 dp; `total` = sum of line totals.

Response `FnbPurchaseResponse` (`@JsonInclude(NON_NULL)`), returned by POST and `GET /{purchaseId}`:
```
purchaseId, supplierName?, purchasedOn (date), note?, total, createdAt (Instant), actorName?
lines: [{ inventoryItemId, name, unit, quantity, unitCost, lineTotal, movementId }]  // ordered by name
marginWarnings: string[]   // POST only (distinct, from restockWarnings for items whose average changed); absent on GET/replay
```
`GET ?page=0&size=20` (size 1..50, page >= 0): `FnbPurchaseSummaryResponse[]` `{ purchaseId, supplierName?, purchasedOn, total, lineCount, createdAt, actorName? }`, newest `purchasedOn` then `createdAt` first. No N+1 (one query for headers, one aggregate for counts or a grouped select; names resolved in one query).

## Design decisions
- D1 Migration `V16__purchases.sql`: `purchases` (id, created_at, tenant_id, outlet_id, supplier_name varchar(120), purchased_on date NOT NULL, note varchar(500), total numeric(19,2) NOT NULL CHECK >= 0, actor_membership_id, idempotency_key varchar(128), request_fingerprint varchar(64), `UNIQUE (tenant_id, idempotency_key)`, FKs tenant/outlet, index `(tenant_id, outlet_id, purchased_on DESC, created_at DESC)`), `purchase_lines` (id, created_at, tenant_id, purchase_id, inventory_item_id, quantity numeric(14,3) CHECK > 0, unit_cost numeric(19,4) CHECK >= 0, line_total numeric(19,2), movement_id NOT NULL FK, `UNIQUE (purchase_id, inventory_item_id)`, FKs). Mirror V13 naming. No change to `inventory_movements`.
- D2 Code placement (existing layout, no new packages): entities `PurchaseEntity`, `PurchaseLineEntity` next to `StockCountEntity`; persistence/query methods in `FnbInventoryRepository`; the use case in `FnbInventoryService` (reuse its private `item(...)`, `lockedBalance(...)`, `actorName(...)`, fingerprint/digest helpers; no duplicated helpers); routes in `FnbInventoryController`; DTOs in `dto/request` / `dto/response` (named classes, `@Getter @Setter`, no records); mapping in `FnbInventoryMapper`.
- D3 Purchases are immutable in this task: no edit, no delete, no void (a mistake is corrected with a WASTE/ADJUSTMENT movement). No supplier master table. No Postman, proto or Python change. No tests (policy).
- D4 UI: new owner-only page `/purchases` ("Pembelian"): list (date, supplier, total, line count, actor; paging "muat lagi"), "Catat pembelian" form (date default today, supplier, note, lines: item picker from `useInventoryItemsQuery`, quantity with the item unit, unit cost, live line/total PREVIEW only; the saved total is the server's), idempotency key generated once per form open and reused on retry, detail modal, margin warnings shown after save, then invalidate inventory item queries (stock and average cost changed) and the purchase list.

## Wave 1 (parallel)

### J1 (`java-worker`)
Reserved: `src/main/resources/db/migration/V16__purchases.sql` (new); `entity/shared/inventory/PurchaseEntity.java`, `PurchaseLineEntity.java` (new; same package as `StockCountEntity`); `dto/request/FnbPurchaseRequest.java`, `FnbPurchaseLineRequest.java`, `dto/response/FnbPurchaseResponse.java`, `FnbPurchaseLineResponse.java`, `FnbPurchaseSummaryResponse.java` (new); `repository/FnbInventoryRepository.java`, `service/FnbInventoryService.java`, `controller/FnbInventoryController.java`, `dto/FnbInventoryMapper.java` (additions only); `entity/shared/audit/AuditLogEntity.java` (one enum value). All paths under `src/main/java/org/lite/project/cashiersaas/`.
Report: `reports/TASK-100-J1.md` (include the exact SQL and list unit tests whose `@InjectMocks` setup may need a change).
Acceptance: contract above exactly; stock and moving average change as a RESTOCK would; replay and 409 behave as stated; tenant/outlet isolation (404, no existence leak); owner only; no N+1 in the list; AGENTS.md DTO/code rules.

### F1 (`fe-worker`)
Reserved (UI repo): `src/pages/purchases/**` (new), `src/api/purchase.api.ts` (new), `src/api/mock/purchase.mock.ts` (new; in-memory, mirrors the contract incl. replay by key), `src/types/purchase.ts` (new), `src/routes/AppRoutes.tsx` (one route inside the existing `OwnerRoute` block), `src/components/layout/Sidebar.tsx` (one `ownerOnly` nav item), `src/language/id.ts`, `en.ts` (new `purchasesPage` block + nav key).
Report: `reports/TASK-100-F1.md`.
Acceptance: owner can record a purchase and see it in the list and detail; stock page shows the new quantity after save (query invalidation); server validation messages shown (400/404/409); cashier cannot see or open `/purchases`; money via `formatCurrency`; no literal copy; no client-computed total sent to the server. Do not run tests; the orchestrator runs `tsc -b`/`oxlint`.
