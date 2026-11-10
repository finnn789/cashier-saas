# TASK-061: F&B ingredient inventory, recipes and stock warnings

Status: DELEGATED (2026-11-10). Memory version at delegation: 52 (-> 53 after this packet). Baseline: git HEAD `95bd9af`; `src/` and `pom.xml` clean at delegation (only `postman/**` and `.postman/resources.yaml` are dirty — never touch them).
Scope note: PRD-MVP §5 listed "Inventori bahan baku dan resep" as post-MVP. The user explicitly pulled it into scope on 2026-11-10 (recorded in `.ai/DECISIONS.md`).

## User decisions (binding)

1. Stock MAY go negative. Never block a sale/payment because of stock. Warn when LOW / OUT / NEGATIVE. No DB check `quantity_on_hand >= 0`.
2. CASHIER may RESTOCK and WASTE. ADJUSTMENT, ingredient CRUD and recipes are OWNER only.
3. One unit per ingredient (`unit` column), no conversion.
4. Add-on stock = recipe line on the option (e.g. "Boba" = 50 g of ingredient "Boba"). Reusable option GROUPS across products is Phase 2 (NOT in this task).
5. Workers MUST reuse existing code. No new file or function unless listed below. No duplicate of an existing function. See "Reuse rules".

## Facts verified by the orchestrator (do not re-audit; open only the files you edit/call)

- Role by path (`SecurityConfiguration`): `/api/v1/merchant/**` = OWNERMERCHANT only; `/api/v1/cashier/**` = OWNERMERCHANT or CASHIER. `MerchantAccessGuard.requireOutlet` (any member of the outlet) / `.requireOwner` (else `ResourceNotFoundException`). Both return `MerchantAccessContext` (tenant, outlet, membership; `context.getMembership().getId()`/`.getUser()`).
- A PAID order cannot be cancelled (`SalesOrderEntity.cancel` -> `requireNewUnpaid`). No reversal path exists; do NOT implement SALE_REVERSAL.
- An order becomes paid in TWO places, both inside a transaction that already holds `orders.lockTenant(...)`: `FnbPaymentService.settle(...)` and the private `MockFnbGatewayService.settle(context, order, attempt)`. Both must call the stock hook.
- Reading order lines: `FnbOrderRepository.items(tenantId, outletId, List<UUID> orderIds)` and `.itemOptions(tenantId, outletId, List<UUID> itemIds)` already exist. `OrderItemEntity.getProduct()` and `OrderItemOptionEntity.getProductOption()` may be null (skip those).
- `FnbOrderPricingService`, `FnbPosOrderService`, `PublicOrderService` are NOT touched by this task.
- Existing but UNUSED inventory code: `InventoryItemEntity`, `InventoryBalanceEntity`, `InventoryMovementEntity` (package `entity/shared/inventory`) and tables `inventory_items`, `inventory_balances`, `inventory_movements` from V1. `inventory_movements.movement_type` is `varchar(16)`, unique `(tenant_id, idempotency_key)`, `request_fingerprint` NOT NULL (64), `reason` NOT NULL, `actor_membership_id` NOT NULL, `occurred_at` NOT NULL. `inventory_balances` unique `(tenant_id, outlet_id, inventory_item_id)`, extends `MutableBaseEntity` (`@Version`). Balance and movement hold `tenantId`/`outletId` as raw UUID columns; item holds `TenantEntity`/`OutletEntity`.
- Current Flyway: V1, V2, V3. New = V4.
- Existing nested-class DTO precedent: `PublicMenuResponse.Category` etc. (static nested public classes are acceptable for line items). Existing nested-enum precedent: `AuditLogEntity.Action`.
- Paging precedent: `FnbStaffOrderService.list` (page >= 0, 1 <= size <= 50, fetch size+1). The movement list returns a plain list for `page`/`size` (no new page-response class).
- SHA-256 hex: inject `PublicOrderTokenService` and call `digest(String)`. Never add another MessageDigest copy.

## Reuse rules for EVERY worker

- You inherit no conversation. Read this file (common rules + your section), `AGENTS.md` (Java conventions) and only the source files you edit or call.
- Write ONLY your reserved paths. Everything else is read-only. Never edit `.ai/*`, `tasks/*`, real `.env*`, `postman/**`, `docs/contracts/*`.
- Before writing any helper, search for an existing one (`Grep` / codebase-memory `search_graph` with `file_pattern: src/main/java/**`; ignore `reports/artifacts/**` copies).
- Follow the style of `FnbCatalogRepository` (Blaze `CriteriaBuilderFactory` + Querydsl `Q*` classes generated at compile; `lock(x)` = `entityManager.refresh(x, LockModeType.PESSIMISTIC_*)`), `FnbCatalogService` (guard first, `@Transactional`, entity factories, static mapper), `ApiResponse.success/created`, `@ResponseStatus(HttpStatus.CREATED)` on creates.
- Java: 17; DTOs named `public class` + Lombok `@Getter/@Setter` (never `record`, never `@Data`); no `ResponseEntity`; no wildcard generics; no `@Deprecated`; no public setters on entities (named behaviour methods + static `create`, like `CategoryEntity`); money/quantities `BigDecimal`; messages in Indonesian like existing ones; throw `InvalidOperationException` / `OperationConflictException` / `ResourceNotFoundException` — no new exception types or handlers.
- DO NOT RUN MAVEN or any build/test (concurrent builds corrupt `target/`; the orchestrator compiles). Re-read your code for compile errors (imports, generics, Lombok, Q-class names like `QRecipeLineEntity`).
- No `git add/commit/push`. Never log secrets.
- Update `reports/TASK-061-<W>.md` at each checkpoint and at the end: changed files, acceptance progress, evidence (what you re-read/verified), remaining work, deviations. If the contract conflicts with real code, STOP and report instead of improvising across reserved boundaries.

## Waves

| Wave | Worker | Model | Scope |
|---|---|---|---|
| 1 | W1 | sonnet | Migration V4, inventory entities, `RecipeLineEntity`, `FnbInventoryRepository`, demo seed |
| 2 | W2 | sonnet | Inventory use-cases + endpoints (items, movements, stock list) |
| 2 | W3 | sonnet | Recipe endpoints in the catalog slice + audit action |
| 2 | W4 | sonnet | `FnbStockService` (deduct on paid) + hooks in both settle paths + `stockWarnings` |
| 3 | orchestrator | — | `mvnw.cmd -q compile`, diff review vs baseline, contract check, canonical update |

Wave 2 starts only after W1 is reviewed and compiled. W2/W3/W4 have disjoint reserved writes.

---

## Shared contract (W1 implements EXACTLY; W2–W4 code against it)

### Entities (package `org.lite.project.cashiersaas.entity.shared.inventory`)

`InventoryMovementEntity` (keep `BaseEntity`, table, columns):
- Add nested `public enum Type { RESTOCK, WASTE, ADJUSTMENT, SALE }`; change field `movementType` to `@Enumerated(EnumType.STRING) @Column(name="movement_type", nullable=false, length=16) private Type type;` (getter `getType()`).
- `public static InventoryMovementEntity record(UUID tenantId, UUID outletId, InventoryItemEntity item, UUID orderId, Type type, BigDecimal quantityDelta, String reason, String idempotencyKey, String requestFingerprint, UUID actorMembershipId)` — sets `occurredAt = Instant.now()`; `reversalOfMovementId` stays null (column kept, unused).

`InventoryBalanceEntity` (keep table/unique/columns):
- `public static InventoryBalanceEntity create(UUID tenantId, UUID outletId, InventoryItemEntity item)` — `quantityOnHand = BigDecimal.ZERO`.
- `public void apply(BigDecimal delta)` — `quantityOnHand = quantityOnHand.add(delta)`.
- Nested `public enum Status { OK, LOW, OUT, NEGATIVE }` and `public Status status()`: `<0` NEGATIVE; `==0` OUT; `<= inventoryItem.getReorderLevel()` LOW; else OK (compare with `compareTo`).

`InventoryItemEntity`:
- `public static InventoryItemEntity create(TenantEntity tenant, OutletEntity outlet, String sku, String name, String unit, BigDecimal reorderLevel)`; `public void update(String sku, String name, String unit, BigDecimal reorderLevel)`; `public void deactivate()` (`active=false`). New items are active.

`RecipeLineEntity` (NEW, `extends BaseEntity`, table `recipe_lines`): `tenant` (TenantEntity), `outlet` (OutletEntity), `product` (ProductEntity, nullable), `productOption` (ProductOptionEntity, nullable), `inventoryItem` (InventoryItemEntity), `quantityPerUnit` (BigDecimal 14,3). All `@ManyToOne(LAZY)`. Factories: `forProduct(TenantEntity, OutletEntity, ProductEntity, InventoryItemEntity, BigDecimal)` and `forOption(TenantEntity, OutletEntity, ProductOptionEntity, InventoryItemEntity, BigDecimal)`. `@Getter @Entity @NoArgsConstructor`.

### Migration `V4__fnb_inventory_recipe.sql` (new; do not edit V1–V3)
- `CREATE TABLE public.recipe_lines` (id uuid PK, created_at timestamptz NOT NULL, tenant_id, outlet_id, product_id NULL, product_option_id NULL, inventory_item_id NOT NULL, quantity_per_unit numeric(14,3) NOT NULL) + FKs to the real parent tables (check exact names in V1) + `CHECK ((product_id IS NULL) <> (product_option_id IS NULL))` + `CHECK (quantity_per_unit > 0)` + partial unique indexes `(product_id, inventory_item_id) WHERE product_id IS NOT NULL` and `(product_option_id, inventory_item_id) WHERE product_option_id IS NOT NULL` + index on `inventory_item_id`.
- `ALTER TABLE inventory_movements ADD CONSTRAINT ck_inventory_movements_type CHECK (movement_type IN ('RESTOCK','WASTE','ADJUSTMENT','SALE'))`; index `(tenant_id, outlet_id, inventory_item_id, occurred_at DESC)`; partial index on `order_id WHERE order_id IS NOT NULL`.
- `ALTER TABLE inventory_items ADD CONSTRAINT uk_inventory_items_outlet_name UNIQUE (outlet_id, name)`.
- NO check on `inventory_balances.quantity_on_hand`.

### Repository `repository/FnbInventoryRepository.java` (NEW, `@Repository @RequiredArgsConstructor`, style of `FnbCatalogRepository`; every query filters by tenant AND outlet)
```
Optional<InventoryItemEntity> item(UUID tenant, UUID outlet, UUID id)          // active only
List<InventoryItemEntity> items(UUID tenant, UUID outlet)                        // active only, order by name
Optional<InventoryBalanceEntity> balance(UUID tenant, UUID outlet, UUID itemId)
List<InventoryBalanceEntity> balances(UUID tenant, UUID outlet, List<UUID> itemIds)   // empty list in -> empty out
void lock(InventoryBalanceEntity balance)                                        // refresh PESSIMISTIC_WRITE
List<InventoryMovementEntity> movements(UUID tenant, UUID outlet, UUID itemId, int page, int size) // occurredAt desc, id desc
Optional<InventoryMovementEntity> movementByKey(UUID tenant, String idempotencyKey)
List<RecipeLineEntity> productRecipes(UUID tenant, UUID outlet, List<UUID> productIds)
List<RecipeLineEntity> optionRecipes(UUID tenant, UUID outlet, List<UUID> optionIds)
void deleteProductRecipe(UUID tenant, UUID outlet, UUID productId)               // + flush so re-insert cannot violate the unique index
void deleteOptionRecipe(UUID tenant, UUID outlet, UUID optionId)
void persist(InventoryItemEntity) / persist(InventoryBalanceEntity) / persist(InventoryMovementEntity) / persist(RecipeLineEntity)
void flush()
```

### DTOs / services referenced across workers (names are fixed)
- `dto/response/FnbPaymentResponse`: add `private List<String> stockWarnings = new ArrayList<>();` (W4 only).
- `AuditLogEntity.Action.FNB_RECIPE_CHANGED("RECIPE")` (W3 only).
- `FnbStockService` (W4, package `service`): `public List<String> consumeForPaidOrder(MerchantAccessContext context, SalesOrderEntity order)`.
- Idempotency keys: SALE = `sale:{orderId}:{inventoryItemId}`; initial stock = `init:{inventoryItemId}`.

---

## W1 (sonnet): schema, entities, repository, demo seed

Reserved writes:
- `src/main/resources/db/migration/V4__fnb_inventory_recipe.sql` (new)
- `src/main/java/org/lite/project/cashiersaas/entity/shared/inventory/InventoryItemEntity.java`, `InventoryBalanceEntity.java`, `InventoryMovementEntity.java`
- `src/main/java/org/lite/project/cashiersaas/entity/shared/inventory/RecipeLineEntity.java` (new)
- `src/main/java/org/lite/project/cashiersaas/repository/FnbInventoryRepository.java` (new)
- `src/main/resources/db/demo/R__demo_seed.sql` (append-only, idempotent `ON CONFLICT DO NOTHING`; keep the file valid)
- `reports/TASK-061-W1.md`

Do: implement the shared contract exactly. Verify V1 table/FK names before writing V4 (read only the relevant V1 parts). Demo seed: ingredients (Biji Kopi g, Susu ml, Gula Aren ml, Oat Milk ml, Bubuk Matcha g, Boba g, Croissant pcs) for the demo tenant/outlet, one `inventory_balances` row each, one RESTOCK "Stok awal" movement each (balance MUST equal the sum of movements; use the demo owner membership for `actor_membership_id`), and recipe lines for demo products/options (e.g. Espresso 18 g kopi; Kopi Susu Gula Aren 18 g kopi + 120 ml susu + 20 ml gula aren; option Extra Shot 18 g kopi; option Oat Milk 150 ml oat; Matcha Latte 5 g matcha + 150 ml susu). Make one item deliberately low (balance <= reorder level) so the UI warning can be seen.
Acceptance: compiles by inspection (Q-classes: `QRecipeLineEntity`, `QInventoryItemEntity`, `QInventoryBalanceEntity`, `QInventoryMovementEntity` are generated), V4 consistent with V1 names, repository signatures identical to the contract, report lists every touched file.

## W2 (sonnet): inventory use-cases and endpoints

Reserved writes (all new unless noted):
- `controller/FnbInventoryController.java`, `service/FnbInventoryService.java`, `dto/FnbInventoryMapper.java` (final class, static methods, like `FnbCatalogMapper`)
- `dto/request/FnbInventoryItemRequest.java`, `dto/request/FnbStockMovementRequest.java`
- `dto/response/FnbInventoryItemResponse.java`, `dto/response/FnbStockMovementResponse.java`
- `reports/TASK-061-W2.md`

Endpoints (`@RequestMapping("/api/v1")`):
| Method + path | Guard | Behaviour |
|---|---|---|
| `POST /merchant/outlets/{outletId}/fnb/inventory/items` | owner | create item + zero balance; if `initialQuantity > 0` also a RESTOCK movement (reason "Stok awal", key `init:{itemId}`) and balance `apply`; 201 |
| `PUT /merchant/outlets/{outletId}/fnb/inventory/items/{id}` | owner | update; reject unit change when any movement exists (use `movements(...,0,1)`); `initialQuantity` ignored |
| `DELETE /merchant/outlets/{outletId}/fnb/inventory/items/{id}` | owner | `deactivate()`; returns the item |
| `GET /cashier/outlets/{outletId}/fnb/inventory/items?status=` | outlet | list with `quantityOnHand` + `stockStatus`; optional filter by `InventoryBalanceEntity.Status` (in memory); balances fetched in ONE `balances(...)` call (no N+1) |
| `POST /cashier/outlets/{outletId}/fnb/inventory/items/{id}/movements` | `type == ADJUSTMENT ? guard.requireOwner : guard.requireOutlet` | record movement, see below; 201 |
| `GET /cashier/outlets/{outletId}/fnb/inventory/items/{id}/movements?page=&size=` | outlet | page >= 0, 1 <= size <= 50 (else `InvalidOperationException`), plain list |

Request fields. Item: `name` (`@NotBlank @Size(max=140)`), `sku` (`@Size(max=80)`), `unit` (`@NotBlank @Size(max=30)`), `reorderLevel` (`@NotNull @DecimalMin("0") @Digits(integer=11,fraction=3)`), `initialQuantity` (`@DecimalMin("0") @Digits(integer=11,fraction=3)`, default null = 0). Movement: `type` (`InventoryMovementEntity.Type`, `@NotNull`; `SALE` -> `InvalidOperationException`), `quantity` (`@NotNull @Digits(integer=11,fraction=3)`; RESTOCK/WASTE must be > 0, ADJUSTMENT signed and non-zero), `reason` (`@Size(max=1000)`; required for WASTE and ADJUSTMENT, default "Restock" for RESTOCK), `idempotencyKey` (`@NotBlank @Size(max=128)`).
Movement logic: delta = +q (RESTOCK), -q (WASTE), q as given (ADJUSTMENT). Fingerprint = `publicOrderTokenService.digest(type|itemId|delta.stripTrailingZeros().toPlainString()|reason)`. Replay: `movementByKey(tenant, key)` present -> same fingerprint returns the existing movement response, different fingerprint -> `OperationConflictException("Kunci idempotensi telah digunakan.")`. Otherwise lock the balance row (`lock`), `apply(delta)`, persist `InventoryMovementEntity.record(...)` with `context.getMembership().getId()` as actor, return the response. Responses: item {id, sku, name, unit, reorderLevel, active, quantityOnHand, stockStatus}; movement {id, inventoryItemId, type, quantityDelta, reason, orderId, occurredAt, actorMembershipId}.
Acceptance: guard order and paths exactly as above (cashier cannot reach owner endpoints; ADJUSTMENT by a cashier -> 404 via `requireOwner`), tenant+outlet scoping everywhere, no new helper duplicating an existing one.

## W3 (sonnet): recipe endpoints in the catalog slice

Reserved writes:
- `controller/FnbCatalogController.java`, `service/FnbCatalogService.java`, `dto/FnbCatalogMapper.java` (edit only)
- `entity/shared/audit/AuditLogEntity.java` (add ONE enum constant `FNB_RECIPE_CHANGED("RECIPE")`)
- `dto/request/FnbRecipeRequest.java` (new; `@Valid @NotNull @Size(max=50) List<Line> lines`, nested public static class `Line { @NotNull UUID inventoryItemId; @NotNull @DecimalMin(value="0", inclusive=false) @Digits(integer=11,fraction=3) BigDecimal quantityPerUnit; }`)
- `dto/response/FnbRecipeResponse.java` (new; `lines` of nested `Line { inventoryItemId, inventoryItemName, unit, quantityPerUnit }`)
- `reports/TASK-061-W3.md`

Endpoints (all `/merchant/...`, owner via `guard.requireOwner`): `PUT` and `GET` `/merchant/outlets/{outletId}/fnb/products/{productId}/recipe`; `PUT` and `GET` `/merchant/outlets/{outletId}/fnb/products/{productId}/groups/{groupId}/options/{id}/recipe`. PUT replaces the whole recipe (empty list clears it).
Rules: reuse `product()`, `group()` and the option-ownership check used by `updateOption` (`option.getOptionGroup().getId().equals(groupId)`); validate each `inventoryItemId` through `FnbInventoryRepository.item(tenant, outlet, id)` (active, same tenant+outlet, else `ResourceNotFoundException`); reject duplicate ids (`InvalidOperationException`); delete via `deleteProductRecipe/deleteOptionRecipe` then persist new lines; ONE private method shared by the product and option variants (no duplicated logic); audit via the existing audit pattern with `FNB_RECIPE_CHANGED`, `entityId` = product or option id, reason null, details `lines=<n>` (check how `recordPrice` builds the entry and reuse/generalize rather than copy). Inject `FnbInventoryRepository` into `FnbCatalogService`.
Acceptance: existing catalog endpoints unchanged; no new service class.

## W4 (sonnet): deduction on paid + warnings

Reserved writes:
- `service/FnbStockService.java` (new, `@Service @RequiredArgsConstructor`)
- `service/FnbPaymentService.java`, `service/MockFnbGatewayService.java` (minimal edits in the two `settle` methods)
- `dto/response/FnbPaymentResponse.java` (add `stockWarnings`)
- `reports/TASK-061-W4.md`

`consumeForPaidOrder(context, order)` (runs inside the caller's transaction, after `order.confirmPaid()` and after the payment is persisted/flushed; must NEVER throw because of stock level):
1. `items = orders.items(tenantId, outletId, List.of(order.getId()))`; `options = orders.itemOptions(tenantId, outletId, itemIds)`; skip null product/option.
2. Need per inventory item (use a `TreeMap<UUID, BigDecimal>` for deterministic lock order): for each line `quantity x quantityPerUnit` of `productRecipes(...)` for the product, plus for each selected option `line.quantity x quantityPerUnit` of `optionRecipes(...)` (fetch recipes in two list queries, not per line).
3. If the map is empty return `List.of()`. Fetch `balances(...)` in one call; for each item in key order: `movementByKey(tenant, "sale:{orderId}:{itemId}")` present -> skip; else `lock(balance)`, `apply(delta.negate())`, persist `InventoryMovementEntity.record(tenantId, outletId, item, order.getId(), SALE, delta.negate(), "Penjualan " + order.<order number getter>, key, publicOrderTokenService.digest("sale|"+orderId+"|"+itemId+"|"+delta.toPlainString()), context.getMembership().getId())`. A recipe line whose balance row is missing: create it through `InventoryBalanceEntity.create` + persist (do not fail the payment).
4. After applying, for every touched balance with `status() != OK` add a warning string in Indonesian, e.g. `"Susu: sisa -20 ml (minus)"`, `"... (habis)"`, `"... (menipis)"`. Return the list.
Hooks: in `FnbPaymentService.settle` and `MockFnbGatewayService.settle` call it once each (same position relative to flush/audit in both) and set `response.setStockWarnings(...)` on the returned response. Replay/idempotent-return paths keep the empty default. Check the actual order-number getter name on `SalesOrderEntity` before using it.
Acceptance: both settle paths call the hook exactly once; no change to `FnbOrderPricingService`, `FnbPosOrderService`, `PublicOrderService`; payments for products without any recipe behave exactly as before (no movement rows, empty warnings).

---

## Orchestrator wave 3 checklist
1. `git diff` against `95bd9af` per worker: only reserved paths changed; new files = exactly the list above.
2. `mvnw.cmd -q compile` (no tests). Fix by delegating, not by hand.
3. Contract check: repository signatures, guard per path, both settle hooks, no second SHA-256, no `record`/`@Data` DTOs, no wildcard generics.
4. Report status as READY FOR TESTING with recommended scenarios: owner creates item + recipe; cashier RESTOCK/WASTE; cashier ADJUSTMENT = 404; pay CASH order with recipe -> SALE movements + balance down; pay via MOCK_GATEWAY resolve SUCCEEDED -> same; stock below reorder level -> `stockWarnings`; negative stock allowed; duplicate idempotency key replay/conflict; cross-tenant 404; product without recipe unaffected; V4 applies on a V3 database.
5. Phase 2 (reusable option groups) is a separate task after this one.
