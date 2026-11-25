# Contract Data — Part C: Catalog, Options, Recipe, Inventory, Stock Count

> TEMPORARY working memory (2026-11-23). Source: FE contract TASK-CONTRACT-DATA v2.5.0. Delete after the DTO/controller audit is resolved.

Backend scope to check: `FnbCatalogController`, `FnbInventoryController`, and their DTOs/enums.

## 4.4 Catalog, options, recipe

```typescript
export interface FnbCategory { id: string; name: string; displayOrder: number; archived: boolean }

export interface CleanFnbProduct {
  id: string; categoryId: string; categoryName?: string
  name: string; description: string; price: number; imageUrl: string
  available: boolean; displayOrder: number; archived: boolean
}

export interface RecipeLine {
  inventoryItemId: string; inventoryItemName?: string; unit?: string
  quantityPerUnit: number
  costEstimate?: number
}
export interface Recipe { lines: RecipeLine[] }

export interface OptionGroup {
  id: string; name: string; required: boolean
  minimumSelections: number; maximumSelections: number
  displayOrder: number; options?: ProductOption[]
}

export interface ProductOption {
  id: string; optionGroupId: string; name: string
  priceAdjustment: number; available: boolean; displayOrder: number
  recipe?: Recipe              // option-specific recipe (e.g. oat milk)
}
```

Product form validation claims: name 1..100, categoryId required when outlet has active categories, price > 0. Server 400 field errors mapped for name, categoryId, price, description. Recipe saved via a separate update-product-recipe call.

## 4.5 Inventory & stock count

```typescript
export type StockStatus = 'OK' | 'LOW' | 'OUT' | 'NEGATIVE'

export interface InventoryItem {
  id: string; sku: string; name: string
  unit: string               // 'g' | 'ml' | 'pcs' | 'kg' | 'l'
  reorderLevel: number; active: boolean
  quantityOnHand: number; stockStatus: StockStatus
  costPerUnit?: number; averageUnitCost?: number; updatedAt?: string
}

export interface StockMovement {
  id: string; inventoryItemId: string; inventoryItemName?: string; unit?: string
  type: 'RESTOCK' | 'WASTE' | 'ADJUSTMENT' | 'SALE' | 'SALE_REVERSAL'
  quantityDelta: number; reason: string; orderId?: string
  occurredAt: string; actorName?: string; unitCost?: number
}

// POST /merchant/outlets/{outletId}/fnb/inventory/stock-counts
export interface StockCountRequest {
  idempotencyKey: string; note?: string
  lines: { inventoryItemId: string; countedQuantity: number }[]
}

export interface StockCountResponse {
  lines: {
    inventoryItemId: string; name: string; unit: string
    previousQuantity: number; countedQuantity: number
    difference: number; movementId: string
  }[]
}
```

## Claims to verify
- HTTP 409 optimistic-conflict handling on concurrent stock movement and recipe updates.
- Idempotency-Key TTL 24 hours on stock counts.
