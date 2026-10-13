# General entity separation — orchestrator review

User request: inspect all entities and move reusable models, including Product, into shared. Worker TASK-036 baseline memory13; source snapshot and SHA256 manifest saved before edits in reports/artifacts/ENTITY-20261008/. Prior unit-test work stays paused.

## Reviewed implementation

- entity/shared/catalog: ProductEntity, CategoryEntity, OptionGroupEntity, ProductOptionEntity, CashierCatalogItemEntity.
- entity/shared/sales: CashierSaleEntity, CashierSaleItemEntity, PickupDeliveryJobEntity.
- F&B order/table/payment/discount/history/gateway entities retain their domain ownership. Laundry services, service categories/pricing, orders/items/history retain laundry ownership. Existing shared identity, merchant, billing, customer, inventory, sales, audit and base classes retain their packages.

50 entity/base source files remain present. Eight moves plus19 dependent-file updates (16 production including two order-item entities, three existing tests). All27 changed/moved source files compare identically after normalization of package/imports and authorized moved-class FQCN substitutions. Mapping annotations, fields, methods, assertions, query behavior and lifecycle code are unchanged. Evidence: normalized-review.csv. No obsolete moved entity/Q-class FQCN references found under src. Required explicit cross-package references reviewed, including CashierSaleItemEntity to catalog and F&B order items to catalog. No handwritten/generated Q types edited.

FnbGatewayAttemptEntity additionally differs at byte level from the manifest; git no-index comparison reports no textual difference. Worker file-change events do not include it. Preserve this external line-ending change; do not attribute it to worker or undo it.

## Limits and observations

Worker final report and source/event review accepted. Worker baseline13 was compared with current memory14: a concurrent read-only Tenant audit increment is compatible and preserved. Canonical acceptance persisted as memory15. Worker final classification covers50 sources. Its shared-field observation omitted laundryOrderItemId; the observation below qualifies that omission. Independent orchestrator snapshot comparison, rather than worker HEAD comparison alone, establishes preservation of pre-existing dirty source. Worker exited0; no code-writing scope violations found in reviewed patch/move events.

Static source review only; no Maven, compilation, tests, startup or database operations. QueryDSL generated output needs regeneration during a later authorized build. Existing shared CustomerInvoiceLineEntity still has laundryOrderItemId: schema generalization is a separate concern. Package relocation does not enable laundry or delivery features and does not unify existing separate catalog/order/payment tables.

Ruflo tools/ToolSearch not exposed in this session; verified alternate Codex CLI worker used. Codebase Memory queried, findings verified against current source because index is stale. Historical .ai/context/workflow.md, .ai/DECISIONS.md and PROJECT_MEMORY.md are absent; existing task patterns and canonical memory used.
