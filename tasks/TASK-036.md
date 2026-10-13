# TASK-036 — Shared entity package refactor

User request 2026-10-08: review every entity and move general entities such as Product to shared. Main session remains orchestrator; you are the implementation worker. Read AGENTS.md and vertical-slice-code-pattern. Historical no-agent skill rules are superseded. Memory baseline13. Prior UNIT testing remains paused; do not resume it.

Baseline: reports/artifacts/ENTITY-20261008/baseline.csv and git-status-before.txt. Preserve all existing dirty content. No concurrent workers are running. Report checkpoints before editing and before handoff in reports/TASK-036.md. Never write canonical .ai files.

Reviewed architecture: move ProductEntity, CategoryEntity, OptionGroupEntity, ProductOptionEntity from entity/fnb to entity/shared/catalog; move CashierCatalogItemEntity from entity/laundry to entity/shared/catalog; move CashierSaleEntity and CashierSaleItemEntity from entity/laundry to existing entity/shared/sales. Move PickupDeliveryJobEntity from entity/laundry to entity/shared/sales: generic fulfillment of shared order/customer IDs, no Laundry entity dependency. New catalog package is justified because no existing shared package owns catalog. Keep separate catalog/sales models separate; no merging or renaming classes. Keep table/column names, constraints, annotations, methods, lifecycle, tenant/outlet security, and API behavior unchanged.

Review every entity source and provide complete classification (move/retain and reason) in report. F&B SalesOrder/order items/payment/discount/gateway/status/table/QR remain F&B because they directly depend on its order/table/lifecycle. Laundry service/pricing/order/history remain laundry. Existing shared entities stay; flag any domain-specific fields in shared as observations without changing schema.

Exact allowed writes:
- The eight original entity files and their eight specified destination files.
- src/main/java/org/lite/project/cashiersaas/entity/fnb/OrderItemEntity.java and OrderItemOptionEntity.java: imports for moved entities only.
- Existing .java files directly under repository/, service/, dto/: ONLY import/FQCN updates for moved entities and generated Q types. Do not modify logic or other imports unnecessarily.
- Existing src/test/java files: ONLY imports/FQCN updates for moved entities/Q types; no test creation, assertion or logic changes. These are dependency fixes authorized by this refactor, not resumed UNIT task execution.
- reports/TASK-036.md.

Read any files needed. Do not edit SQL, resources, pom, config, unrelated docs or generated Q files. Do not run tests/build/Maven/startup/database commands or nested agents. No Ruflo hooks/daemon activation. Ruflo tools unavailable to parent this session; Codebase Memory was queried but index is stale, verify actual source.

Acceptance: every current entity reviewed; all eight moves complete; no obsolete source-package references to moved classes or Q classes remain under src; add explicit imports where old same-package visibility applied, including cross-package cashier sale/catalog link; mappings and bodies unchanged. Use static checks only. Report changed files, classification, stale-reference results, evidence and limitations, READY FOR TESTING. Preserve user work and checkpoint progress.
