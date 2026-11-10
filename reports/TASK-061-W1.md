# TASK-061 W1 report: schema, entities, repository, demo seed

Status: SUBMITTED (not compiled by W1 by rule; orchestrator compiles). Tests/Maven: not run by policy.

## Changed files
- NEW `src/main/resources/db/migration/V4__fnb_inventory_recipe.sql`
- MOD `entity/shared/inventory/InventoryItemEntity.java` (create/update/deactivate; unique constraint name `uk_inventory_items_outlet_name` declared in @Table)
- MOD `entity/shared/inventory/InventoryBalanceEntity.java` (create/apply/status, nested enum Status; unused Instant import removed)
- MOD `entity/shared/inventory/InventoryMovementEntity.java` (nested enum Type, field `movementType` -> `type` with @Enumerated(STRING), static `record(...)`)
- NEW `entity/shared/inventory/RecipeLineEntity.java`
- NEW `repository/FnbInventoryRepository.java`
- MOD `src/main/resources/db/demo/R__demo_seed.sql` (append-only)
- NEW `reports/TASK-061-W1.md`

## Acceptance progress
- Contract implemented as written: all entity factories/behaviour, enum names, signatures of `record`, `create`, `apply`, `status`, `forProduct`, `forOption`.
- Repository signatures identical to the contract (item, items, balance, balances, lock, movements, movementByKey, productRecipes, optionRecipes, deleteProductRecipe, deleteOptionRecipe, 4x persist, flush). Every query filters tenant AND outlet (`movementByKey` filters tenant only, as in contract). `balances`/`productRecipes`/`optionRecipes` return `List.of()` for an empty id list.
- `deleteProductRecipe/deleteOptionRecipe`: load matching lines (tenant+outlet+owner) via the existing recipe queries, `entityManager.remove` each, then `entityManager.flush()` (Hibernate flushes inserts before deletes, so the flush is what lets a replacement re-insert the same unique pairs).
- `movements(...)`: order occurredAt desc, id desc, `setFirstResult(page*size).setMaxResults(size)` (exactly `size` rows; caller W2 may ask `size+1` if it wants a has-more flag).
- Demo seed: 7 ingredients (Biji Kopi g, Susu ml, Gula Aren ml, Oat Milk ml, Bubuk Matcha g, Boba g, Croissant pcs), one balance each, one RESTOCK "Stok awal" movement each (balance = sum of movements), actor = demo owner membership `...e1`, key `init:{itemId}` (matches contract), fingerprint = sha256 hex via Postgres `sha256(convert_to(...))`. Gula Aren is deliberately LOW (400 ml vs reorder 500). Other levels: kopi 5000/500, susu 20000/2000, oat 6000/1000, matcha 1000/200, boba 3000/500, croissant 24/5. 11 recipe lines: Espresso 18 g kopi; Kopi Susu Gula Aren 18 g kopi + 120 ml susu + 20 ml gula aren; Cappuccino 18 g kopi + 150 ml susu; Matcha Latte 5 g matcha + 150 ml susu; Croissant Butter 1 pcs; option Extra Shot 18 g kopi; option Oat Milk 150 ml oat. All inserts `ON CONFLICT DO NOTHING` with new fixed ids (items 7001-7007, balances 7101-7107, movements 7201-7207, recipes 7301-7311; no clash with existing 1001/2001/3001/4001/5001/6001 ids).

## Evidence (read, not run)
- V1 tables used by FKs: `public.tenants`, `public.outlets`, `public.products`, `public.product_options`, `public.inventory_items` (all `id uuid` PK). V1 FK names are Hibernate-generated hashes, so V4 uses explicit names (`fk_recipe_lines_*`).
- V1 `inventory_balances`/`inventory_items`/`inventory_movements` column lists confirmed (movement_type varchar(16), reason text NOT NULL, request_fingerprint varchar(64) NOT NULL, balance/item have version+updated_at, recipe_lines has no updated_at/version because RecipeLineEntity extends BaseEntity).
- `application.properties` has `spring.jpa.hibernate.ddl-auto=validate`: V4 `recipe_lines` columns match entity types (uuid, numeric(14,3), timestamptz). `@Enumerated(STRING)` on varchar(16) validates.
- No other source file referenced `movementType`/`getMovementType` (Grep over src), so the rename breaks no caller. Querydsl Q classes expected: `QRecipeLineEntity`, `QInventoryItemEntity`, `QInventoryBalanceEntity`, `QInventoryMovementEntity` in package `entity.shared.inventory`.
- Seed R__ file has LF endings, consistent with the original; original content untouched, new block appended.

## Deviations / notes for orchestrator and W2-W4
- None against the contract.
- Added `name="uk_inventory_items_outlet_name"` unique constraint to the `@Table` on InventoryItemEntity to mirror V4 (documentation only; validate mode does not check it).
- Lazy associations: `RecipeLineEntity.getInventoryItem()` is a lazy proxy; `.getId()` does not initialize it, `.getName()/.getUnit()` triggers one select per distinct item inside the transaction (W3 response mapping). Not changed to a fetch join to stay with the existing repository style. Same for `InventoryBalanceEntity.status()` which touches `inventoryItem.getReorderLevel()` (one select per item; W2/W4 call it in-transaction).
- Duplicate item name in the same outlet now raises a DB unique violation (V4); W2 should check/translate if it wants a friendly 409 (not part of W1).

## Remaining work
- None for W1. Orchestrator: compile, then review.
