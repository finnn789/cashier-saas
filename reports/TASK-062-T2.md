# TASK-062 T2 report: FnbInventoryService + InventoryBalanceEntity unit tests

Status: written, NOT compiled or run (per rules). No production code touched.

## Files written
- `src/test/java/org/lite/project/cashiersaas/unit/service/FnbInventoryServiceTest.java` (MockitoExtension; mocks `MerchantAccessGuard`, `FnbInventoryRepository`, `PublicOrderTokenService`)
- `src/test/java/org/lite/project/cashiersaas/unit/entity/InventoryBalanceEntityTest.java`
- `reports/TASK-062-T2.md`

## FnbInventoryServiceTest methods
Digest is stubbed with a deterministic fake (`"fp(" + input + ")"`) so fingerprints are comparable and the digest input format `type|itemId|delta|reason` is asserted.

1. createItem
- `createItemRequiresOwnerPersistsItemThenBalanceAndRecordsInitialRestock`: owner guard (not outlet guard); item/balance/movement persisted; RESTOCK "Stok awal", key `init:{itemId}`, fingerprint, actor = membership id; balance == initial; response OK.
- `createItemFlushesAfterPersistingTheItemAndBeforeTheBalance`: InOrder persist(item) -> flush -> persist(balance) -> persist(movement).
- `createItemWithoutInitialQuantityPersistsZeroBalanceAndNoMovement`: null initial -> no movement, tokens untouched, zero/OUT.
- `createItemWithZeroInitialQuantityPersistsNoMovement`: 0 initial -> no movement.
- `createItemStoresBlankSkuAsNullAndTrimsRealSku`: blank sku -> null; padded sku trimmed.
2. updateItem
- `updateItemRejectsUnitChangeWhenAnyMovementExists`: InvalidOperationException, item unchanged, no flush.
- `updateItemAllowsUnitChangeWhenNoMovementExists`: update applied (name/unit trimmed, blank sku null, reorder), flush, initialQuantity ignored (no movement).
- `updateItemWithUnchangedUnitNeverQueriesMovements`: `movements(...)` never called.
- `updateItemWithUnknownIdIsNotFound`: ResourceNotFoundException.
3. deactivate
- `deactivateItemMarksItemInactiveAndReturnsIt`; `deactivateMissingItemIsNotFound`.
4. items
- `itemsFetchesAllBalancesInOneCallAndReportsMissingBalanceAsZeroOut`: one `balances(...)` call with all ids, never single `balance`, item without balance = 0/OUT, outlet guard.
- `itemsStatusFilterReturnsOnlyMatchingStockStatus`: LOW / OUT / NEGATIVE filters.
5. guard selection
- `adjustmentRequiresOwnerGuardAndNeverUsesOutletGuard`; `restockUsesOutletGuardNotOwnerGuard`; `wasteUsesOutletGuardNotOwnerGuard`.
6. delta rules
- `restockAddsQuantity`, `wasteSubtractsQuantity`, `adjustmentKeepsNegativeSignAsGiven`, `adjustmentKeepsPositiveSignAsGiven` (movement delta and balance both checked).
- `saleMovementCannotBeRecordedManually`, `zeroQuantityIsRejectedForEveryAllowedType`, `negativeQuantityIsRejectedForRestockAndWaste` (InvalidOperationException, repository never touched).
7. reason rules
- `blankRestockReasonDefaultsToRestock`, `nullRestockReasonDefaultsToRestock`, `blankWasteReasonIsRejected`, `blankAdjustmentReasonIsRejected`, `reasonIsTrimmedBeforeBeingStored`.
8. idempotency
- `sameKeyAndSameFingerprintReplaysExistingMovementWithoutSideEffects`: returns existing, no persist/lock/flush/balance lookup.
- `sameKeyWithDifferentFingerprintIsAConflictAndChangesNothing`: OperationConflictException.
- `whitespaceOnlyReasonAndScaleDifferencesStillReplay`: "  Basi  " and "5.000" replay.
9. new movement
- `newMovementLocksBalanceBeforeApplyingDeltaThenPersistsAndFlushes`: quantity observed AT lock time is still the pre-apply value (proves lock before apply), then persist, flush; actor = membership id; fingerprint/key/orderId null.
- `missingBalanceRowIsCreatedAndPersistedInsteadOfLocked`: balance created via persist, never locked, equals delta.
- `movementForUnknownItemIsNotFoundAndTouchesNoBalance` (extra).
10. movements
- `movementsRejectOutOfRangePagingBeforeAnyGuardOrQuery` (page -1, size 0, size 51, page 1_000_001; guard and repo untouched), `movementsAcceptBoundaryPagingValues` (0/1 and 1_000_000/50), `movementsOfUnknownItemAreNotFoundWithoutQueryingHistory`, `movementsPreserveRepositoryOrderAndMapEveryField`.

## InventoryBalanceEntityTest methods
- `createStartsAtZeroAndKeepsScopeAndItem`, `applyAccumulatesPositiveAndNegativeDeltasKeepingScale` (1.500+2.250=3.750 exact scale, then -1.250), `negativeQuantityIsNegativeEvenJustBelowZero`, `exactlyZeroIsOutRegardlessOfReorderLevelAndScale`, `quantityEqualToReorderLevelIsLowIgnoringScaleDifferences` (compareTo, 10 vs 10.000), `quantityJustAboveReorderLevelIsOk`, `statusFollowsQuantityAfterEachApply`.

## Fix after orchestrator run (427 unit, 5 errors)
`stubItemCreationIds` passed `invocation.getArgument(0)` directly to `ReflectionTestUtils.setField`, so the generic return picked the `setField(Class, String, Object)` overload and threw ClassCastException in the 5 createItem* tests. Fixed by assigning to a typed local (`InventoryItemEntity created = invocation.getArgument(0);`) first. Re-grepped both test files: the only other `getArgument` use is string concatenation in `stubDigest`, which is fine. No assertions changed.

## Not covered / notes
- Nothing from the packet left uncovered. `Transactional` behaviour, Bean Validation annotations and real SQL are out of unit scope (T4 integration).
- Mockito `@InjectMocks` uses constructor injection (`@RequiredArgsConstructor`) with three mocks.

## Suspected production issues
None found; production code matches the packet. Observations (no assertion weakened):
- `recordMovement` checks `delta` and `reason` BEFORE `item(...)` lookup, so an invalid request for an unknown item yields 400 rather than 404 (tests assert this order only via `verifyNoInteractions(inventory)` on rejected requests, and a separate unknown-item test with a valid request). If the orchestrator wants 404-first, this is a behaviour change.
- `items()` uses `Collectors.toMap` on balances; a duplicate balance row per item would throw IllegalStateException, but the DB unique constraint prevents that.

## Assumptions about signatures
- `TenantPrincipal(UUID userId, UUID tenantId, UUID outletId, UUID, UUID, Set<Role>)` as in `FnbOrderQrServiceTest`.
- `TenantMembershipEntity.grantOwner(null, tenant)`; id set by `ReflectionTestUtils.setField(.., "id", ..)` (field lives in `BaseEntity`).
- `new MerchantAccessContext(tenant, outlet, membership)`; `OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null)`.
- Lombok getters used: `getType/getQuantityDelta/getReason/getOrderId/getOccurredAt/getActorMembershipId/getRequestFingerprint/getIdempotencyKey/getTenantId/getOutletId/getInventoryItem`, item `getUnit/getName/getSku/isActive/getReorderLevel/getTenant/getOutlet`, balance `getQuantityOnHand/getTenantId/getOutletId/getInventoryItem`.
