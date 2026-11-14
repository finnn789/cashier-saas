package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.FnbInventoryItemRequest;
import org.lite.project.cashiersaas.dto.request.FnbStockMovementRequest;
import org.lite.project.cashiersaas.dto.response.FnbInventoryItemResponse;
import org.lite.project.cashiersaas.dto.response.FnbStockMovementResponse;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryBalanceEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryItemEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryMovementEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.FnbInventoryRepository;
import org.lite.project.cashiersaas.service.FnbInventoryService;
import org.lite.project.cashiersaas.service.MerchantAccessGuard;
import org.lite.project.cashiersaas.service.DigestService;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class FnbInventoryServiceTest {
    @Mock private MerchantAccessGuard guard;
    @Mock private FnbInventoryRepository inventory;
    @Mock private DigestService tokens;
    @InjectMocks private FnbInventoryService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID outletId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    private final UUID membershipId = UUID.randomUUID();
    private final TenantPrincipal owner = new TenantPrincipal(UUID.randomUUID(), tenantId, outletId,
            UUID.randomUUID(), UUID.randomUUID(), Set.of(Role.OWNERMERCHANT));
    private final TenantPrincipal cashier = new TenantPrincipal(UUID.randomUUID(), tenantId, outletId,
            UUID.randomUUID(), UUID.randomUUID(), Set.of(Role.CASHIER));
    private final TenantEntity tenant = new TenantEntity();
    private final OutletEntity outlet = OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null);
    private final TenantMembershipEntity membership = membership();
    private final MerchantAccessContext context = new MerchantAccessContext(tenant, outlet, membership);

    private TenantMembershipEntity membership() {
        TenantMembershipEntity entity = TenantMembershipEntity.grantOwner(null, tenant);
        ReflectionTestUtils.setField(entity, "id", membershipId);
        return entity;
    }

    private InventoryItemEntity item(UUID id, String name, String unit, String reorderLevel) {
        InventoryItemEntity item = InventoryItemEntity.create(tenant, outlet, null, name, unit, new BigDecimal(reorderLevel));
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    private InventoryBalanceEntity balanceOf(InventoryItemEntity item, String quantity) {
        InventoryBalanceEntity balance = InventoryBalanceEntity.create(tenantId, outletId, item);
        balance.apply(new BigDecimal(quantity));
        return balance;
    }

    private FnbInventoryItemRequest itemRequest(String name, String sku, String unit, String reorderLevel, String initialQuantity) {
        FnbInventoryItemRequest request = new FnbInventoryItemRequest();
        request.setName(name);
        request.setSku(sku);
        request.setUnit(unit);
        request.setReorderLevel(new BigDecimal(reorderLevel));
        request.setInitialQuantity(initialQuantity == null ? null : new BigDecimal(initialQuantity));
        return request;
    }

    private FnbStockMovementRequest movementRequest(InventoryMovementEntity.Type type, String quantity, String reason, String key) {
        FnbStockMovementRequest request = new FnbStockMovementRequest();
        request.setType(type);
        request.setQuantity(new BigDecimal(quantity));
        request.setReason(reason);
        request.setIdempotencyKey(key);
        return request;
    }

    // Deterministic stand-in for SHA-256: the "digest" exposes its input so fingerprints are comparable.
    private void stubDigest() {
        when(tokens.digest(anyString())).thenAnswer(invocation -> "fp(" + invocation.getArgument(0) + ")");
    }

    private void stubItemCreationIds() {
        doAnswer(invocation -> {
            InventoryItemEntity created = invocation.getArgument(0);
            ReflectionTestUtils.setField(created, "id", itemId);
            return null;
        }).when(inventory).persist(any(InventoryItemEntity.class));
    }

    /** Stubs everything a brand-new (non-replayed) movement needs, with the guard chosen like the service does. */
    private void stubNewMovementPath(InventoryMovementEntity.Type type, TenantPrincipal principal,
                                     InventoryItemEntity item, InventoryBalanceEntity balance) {
        if (type == InventoryMovementEntity.Type.ADJUSTMENT) {
            when(guard.requireOwner(principal, outletId)).thenReturn(context);
        } else {
            when(guard.requireOutlet(principal, outletId)).thenReturn(context);
        }
        when(inventory.item(tenantId, outletId, itemId)).thenReturn(Optional.of(item));
        when(inventory.movementByKey(eq(tenantId), anyString())).thenReturn(Optional.empty());
        when(inventory.balance(tenantId, outletId, itemId)).thenReturn(Optional.ofNullable(balance));
        stubDigest();
    }

    private InventoryMovementEntity persistedMovement() {
        ArgumentCaptor<InventoryMovementEntity> captor = ArgumentCaptor.forClass(InventoryMovementEntity.class);
        verify(inventory).persist(captor.capture());
        return captor.getValue();
    }

    private InventoryMovementEntity existingMovement(InventoryItemEntity item, InventoryMovementEntity.Type type,
                                                     String delta, String reason, String key, String fingerprint) {
        InventoryMovementEntity movement = InventoryMovementEntity.record(tenantId, outletId, item, null, type,
                new BigDecimal(delta), reason, key, fingerprint, membershipId);
        ReflectionTestUtils.setField(movement, "id", UUID.randomUUID());
        return movement;
    }

    // ---------------------------------------------------------------- 1. createItem

    @Test
    void createItemRequiresOwnerPersistsItemThenBalanceAndRecordsInitialRestock() {
        when(guard.requireOwner(owner, outletId)).thenReturn(context);
        stubItemCreationIds();
        stubDigest();

        FnbInventoryItemResponse response = service.createItem(owner, outletId,
                itemRequest(" Biji Kopi ", "KOPI-01", " g ", "100", "1000.000"));

        verify(guard).requireOwner(owner, outletId);
        verify(guard, never()).requireOutlet(any(TenantPrincipal.class), any(UUID.class));

        ArgumentCaptor<InventoryItemEntity> itemCaptor = ArgumentCaptor.forClass(InventoryItemEntity.class);
        ArgumentCaptor<InventoryBalanceEntity> balanceCaptor = ArgumentCaptor.forClass(InventoryBalanceEntity.class);
        ArgumentCaptor<InventoryMovementEntity> movementCaptor = ArgumentCaptor.forClass(InventoryMovementEntity.class);
        verify(inventory).persist(itemCaptor.capture());
        verify(inventory).persist(balanceCaptor.capture());
        verify(inventory).persist(movementCaptor.capture());

        InventoryItemEntity item = itemCaptor.getValue();
        assertEquals("Biji Kopi", item.getName());
        assertEquals("g", item.getUnit());
        assertEquals("KOPI-01", item.getSku());
        assertTrue(item.isActive());
        assertSame(tenant, item.getTenant());
        assertSame(outlet, item.getOutlet());
        assertEquals(0, new BigDecimal("100").compareTo(item.getReorderLevel()));

        InventoryBalanceEntity balance = balanceCaptor.getValue();
        assertEquals(tenantId, balance.getTenantId());
        assertEquals(outletId, balance.getOutletId());
        assertSame(item, balance.getInventoryItem());
        assertEquals(0, new BigDecimal("1000").compareTo(balance.getQuantityOnHand()));

        InventoryMovementEntity movement = movementCaptor.getValue();
        assertEquals(InventoryMovementEntity.Type.RESTOCK, movement.getType());
        assertEquals("Stok awal", movement.getReason());
        assertEquals("init:" + itemId, movement.getIdempotencyKey());
        assertEquals(0, new BigDecimal("1000").compareTo(movement.getQuantityDelta()));
        assertEquals("fp(RESTOCK|" + itemId + "|1000|Stok awal)", movement.getRequestFingerprint());
        assertEquals(membershipId, movement.getActorMembershipId());
        assertNull(movement.getOrderId());
        assertEquals(tenantId, movement.getTenantId());
        assertEquals(outletId, movement.getOutletId());
        assertSame(item, movement.getInventoryItem());

        assertEquals(itemId, response.getId());
        assertEquals("Biji Kopi", response.getName());
        assertEquals(0, new BigDecimal("1000").compareTo(response.getQuantityOnHand()));
        assertEquals(InventoryBalanceEntity.Status.OK, response.getStockStatus());
    }

    @Test
    void createItemFlushesAfterPersistingTheItemAndBeforeTheBalance() {
        when(guard.requireOwner(owner, outletId)).thenReturn(context);
        stubItemCreationIds();
        stubDigest();

        service.createItem(owner, outletId, itemRequest("Susu", null, "ml", "500", "2000"));

        InOrder order = inOrder(inventory);
        order.verify(inventory).persist(any(InventoryItemEntity.class));
        order.verify(inventory).flush();
        order.verify(inventory).persist(any(InventoryBalanceEntity.class));
        order.verify(inventory).persist(any(InventoryMovementEntity.class));
    }

    @ParameterizedTest
    @CsvSource({"Susu,SUSU,ml,500,", "Gula,,g,50,0.000"})
    void createItemWithoutOrZeroInitialQuantityPersistsZeroBalanceAndNoMovement(String name, String sku, String unit,
                                                                              String reorderLevel, String initialQuantity) {
        when(guard.requireOwner(owner, outletId)).thenReturn(context);
        stubItemCreationIds();

        FnbInventoryItemResponse response = service.createItem(owner, outletId,
                itemRequest(name, sku, unit, reorderLevel, initialQuantity));

        verify(inventory, never()).persist(any(InventoryMovementEntity.class));
        verifyNoInteractions(tokens);
        ArgumentCaptor<InventoryBalanceEntity> balanceCaptor = ArgumentCaptor.forClass(InventoryBalanceEntity.class);
        verify(inventory).persist(balanceCaptor.capture());
        assertEquals(0, BigDecimal.ZERO.compareTo(balanceCaptor.getValue().getQuantityOnHand()));
        assertEquals(0, BigDecimal.ZERO.compareTo(response.getQuantityOnHand()));
        assertEquals(InventoryBalanceEntity.Status.OUT, response.getStockStatus());
    }

    @Test
    void createItemStoresBlankSkuAsNullAndTrimsRealSku() {
        when(guard.requireOwner(owner, outletId)).thenReturn(context);
        stubItemCreationIds();

        service.createItem(owner, outletId, itemRequest("Teh", "   ", "g", "10", null));
        service.createItem(owner, outletId, itemRequest("Kopi", "  K-1  ", "g", "10", null));

        ArgumentCaptor<InventoryItemEntity> captor = ArgumentCaptor.forClass(InventoryItemEntity.class);
        verify(inventory, times(2)).persist(captor.capture());
        assertNull(captor.getAllValues().get(0).getSku());
        assertEquals("K-1", captor.getAllValues().get(1).getSku());
    }

    // ---------------------------------------------------------------- 2. updateItem

    @Test
    void updateItemRejectsUnitChangeWhenAnyMovementExists() {
        InventoryItemEntity item = item(itemId, "Susu", "ml", "500");
        InventoryMovementEntity history = existingMovement(item, InventoryMovementEntity.Type.RESTOCK, "10",
                "Stok awal", "init:" + itemId, "fp");
        when(inventory.item(tenantId, outletId, itemId)).thenReturn(Optional.of(item));
        when(inventory.movements(tenantId, outletId, itemId, 0, 1)).thenReturn(List.of(history));

        assertThrows(InvalidOperationException.class, () -> service.updateItem(owner, outletId, itemId,
                itemRequest("Susu UHT", null, "l", "500", null)));

        verify(guard).requireOwner(owner, outletId);
        assertEquals("ml", item.getUnit());
        assertEquals("Susu", item.getName());
        verify(inventory, never()).flush();
    }

    @Test
    void updateItemAllowsUnitChangeWhenNoMovementExists() {
        InventoryItemEntity item = item(itemId, "Susu", "ml", "500");
        InventoryBalanceEntity balance = balanceOf(item, "0");
        when(guard.requireOwner(owner, outletId)).thenReturn(context);
        when(inventory.item(tenantId, outletId, itemId)).thenReturn(Optional.of(item));
        when(inventory.movements(tenantId, outletId, itemId, 0, 1)).thenReturn(List.of());
        when(inventory.balance(tenantId, outletId, itemId)).thenReturn(Optional.of(balance));

        FnbInventoryItemResponse response = service.updateItem(owner, outletId, itemId,
                itemRequest(" Susu UHT ", " ", " l ", "2", "999"));

        verify(guard).requireOwner(owner, outletId);
        assertEquals("l", item.getUnit());
        assertEquals("Susu UHT", item.getName());
        assertNull(item.getSku());
        assertEquals(0, new BigDecimal("2").compareTo(item.getReorderLevel()));
        verify(inventory).flush();
        assertEquals("l", response.getUnit());
        assertEquals("Susu UHT", response.getName());
        // initialQuantity is ignored on update and never creates a movement.
        verify(inventory, never()).persist(any(InventoryMovementEntity.class));
        assertEquals(0, BigDecimal.ZERO.compareTo(response.getQuantityOnHand()));
    }

    @Test
    void updateItemWithUnchangedUnitNeverQueriesMovements() {
        InventoryItemEntity item = item(itemId, "Susu", "ml", "500");
        InventoryBalanceEntity balance = balanceOf(item, "300");
        when(guard.requireOwner(owner, outletId)).thenReturn(context);
        when(inventory.item(tenantId, outletId, itemId)).thenReturn(Optional.of(item));
        when(inventory.balance(tenantId, outletId, itemId)).thenReturn(Optional.of(balance));

        FnbInventoryItemResponse response = service.updateItem(owner, outletId, itemId,
                itemRequest("Susu Segar", "SUSU-2", "ml", "250", null));

        verify(inventory, never()).movements(any(UUID.class), any(UUID.class), any(UUID.class),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        assertEquals("Susu Segar", item.getName());
        assertEquals("SUSU-2", item.getSku());
        assertEquals(0, new BigDecimal("250").compareTo(item.getReorderLevel()));
        assertEquals(0, new BigDecimal("300").compareTo(response.getQuantityOnHand()));
        assertEquals(InventoryBalanceEntity.Status.OK, response.getStockStatus());
    }

    @Test
    void updateItemWithUnknownIdIsNotFound() {
        when(inventory.item(tenantId, outletId, itemId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.updateItem(owner, outletId, itemId,
                itemRequest("Susu", null, "ml", "500", null)));

        verify(guard).requireOwner(owner, outletId);
        verify(inventory, never()).flush();
    }

    // ---------------------------------------------------------------- 3. deactivateItem

    @Test
    void deactivateItemMarksItemInactiveAndReturnsIt() {
        InventoryItemEntity item = item(itemId, "Susu", "ml", "500");
        InventoryBalanceEntity balance = balanceOf(item, "40");
        when(guard.requireOwner(owner, outletId)).thenReturn(context);
        when(inventory.item(tenantId, outletId, itemId)).thenReturn(Optional.of(item));
        when(inventory.balance(tenantId, outletId, itemId)).thenReturn(Optional.of(balance));

        FnbInventoryItemResponse response = service.deactivateItem(owner, outletId, itemId);

        verify(guard).requireOwner(owner, outletId);
        assertEquals(false, item.isActive());
        assertEquals(false, response.isActive());
        assertEquals(itemId, response.getId());
        assertEquals(0, new BigDecimal("40").compareTo(response.getQuantityOnHand()));
    }

    @Test
    void deactivateMissingItemIsNotFound() {
        when(inventory.item(tenantId, outletId, itemId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.deactivateItem(owner, outletId, itemId));

        verify(guard).requireOwner(owner, outletId);
        verify(inventory, never()).balance(any(UUID.class), any(UUID.class), any(UUID.class));
    }

    // ---------------------------------------------------------------- 4. items

    private List<InventoryItemEntity> listFixture() {
        when(guard.requireOutlet(cashier, outletId)).thenReturn(context);
        UUID ok = UUID.randomUUID();
        UUID none = UUID.randomUUID();
        UUID low = UUID.randomUUID();
        InventoryItemEntity okItem = item(ok, "Biji Kopi", "g", "10");
        InventoryItemEntity noBalanceItem = item(none, "Croissant", "pcs", "5");
        InventoryItemEntity lowItem = item(low, "Susu", "ml", "100");
        InventoryBalanceEntity okBalance = balanceOf(okItem, "50");
        InventoryBalanceEntity lowBalance = balanceOf(lowItem, "100");
        when(inventory.items(tenantId, outletId)).thenReturn(List.of(okItem, noBalanceItem, lowItem));
        when(inventory.balances(eq(tenantId), eq(outletId), eq(List.of(ok, none, low))))
                .thenReturn(List.of(okBalance, lowBalance));
        return List.of(okItem, noBalanceItem, lowItem);
    }

    @Test
    void itemsFetchesAllBalancesInOneCallAndReportsMissingBalanceAsZeroOut() {
        List<InventoryItemEntity> items = listFixture();

        List<FnbInventoryItemResponse> responses = service.items(cashier, outletId, null);

        verify(guard).requireOutlet(cashier, outletId);
        verify(inventory, times(1)).balances(eq(tenantId), eq(outletId),
                eq(items.stream().map(InventoryItemEntity::getId).toList()));
        verify(inventory, never()).balance(any(UUID.class), any(UUID.class), any(UUID.class));
        assertEquals(3, responses.size());
        assertEquals("Biji Kopi", responses.get(0).getName());
        assertEquals(InventoryBalanceEntity.Status.OK, responses.get(0).getStockStatus());
        assertEquals(0, new BigDecimal("50").compareTo(responses.get(0).getQuantityOnHand()));
        assertEquals("Croissant", responses.get(1).getName());
        assertEquals(InventoryBalanceEntity.Status.OUT, responses.get(1).getStockStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(responses.get(1).getQuantityOnHand()));
        assertEquals("Susu", responses.get(2).getName());
        assertEquals(InventoryBalanceEntity.Status.LOW, responses.get(2).getStockStatus());
    }

    @Test
    void itemsStatusFilterReturnsOnlyMatchingStockStatus() {
        listFixture();

        List<FnbInventoryItemResponse> low = service.items(cashier, outletId, InventoryBalanceEntity.Status.LOW);
        List<FnbInventoryItemResponse> out = service.items(cashier, outletId, InventoryBalanceEntity.Status.OUT);
        List<FnbInventoryItemResponse> negative = service.items(cashier, outletId, InventoryBalanceEntity.Status.NEGATIVE);

        assertEquals(List.of("Susu"), low.stream().map(FnbInventoryItemResponse::getName).toList());
        assertEquals(List.of("Croissant"), out.stream().map(FnbInventoryItemResponse::getName).toList());
        assertTrue(negative.isEmpty());
    }

    // ---------------------------------------------------------------- 6. delta rules

    @ParameterizedTest
    @CsvSource({"RESTOCK,5,Beli pasar,5,15", "WASTE,3,Basi,-3,7", "ADJUSTMENT,-4,Opname,-4,6", "ADJUSTMENT,4,Opname,4,14"})
    void movementDeltaSignFollowsTypeAndIsAppliedToTheBalance(InventoryMovementEntity.Type type, String quantity,
                                                              String reason, String expectedDelta, String expectedBalance) {
        TenantPrincipal principal = type == InventoryMovementEntity.Type.ADJUSTMENT ? owner : cashier;
        InventoryItemEntity item = item(itemId, "Susu", "ml", "100");
        InventoryBalanceEntity balance = balanceOf(item, "10");
        stubNewMovementPath(type, principal, item, balance);

        service.recordMovement(principal, outletId, itemId, movementRequest(type, quantity, reason, "k1"));

        assertEquals(0, new BigDecimal(expectedDelta).compareTo(persistedMovement().getQuantityDelta()));
        assertEquals(0, new BigDecimal(expectedBalance).compareTo(balance.getQuantityOnHand()));
    }

    @Test
    void saleMovementCannotBeRecordedManually() {
        assertThrows(InvalidOperationException.class, () -> service.recordMovement(cashier, outletId, itemId,
                movementRequest(InventoryMovementEntity.Type.SALE, "5", "Manual", "k1")));

        verifyNoInteractions(inventory);
    }

    @Test
    void zeroQuantityIsRejectedForEveryAllowedType() {
        for (InventoryMovementEntity.Type type : List.of(InventoryMovementEntity.Type.RESTOCK,
                InventoryMovementEntity.Type.WASTE, InventoryMovementEntity.Type.ADJUSTMENT)) {
            assertThrows(InvalidOperationException.class, () -> service.recordMovement(owner, outletId, itemId,
                    movementRequest(type, "0.000", "Alasan", "k1")), type.name());
        }

        verifyNoInteractions(inventory);
    }

    @Test
    void negativeQuantityIsRejectedForRestockAndWaste() {
        assertThrows(InvalidOperationException.class, () -> service.recordMovement(cashier, outletId, itemId,
                movementRequest(InventoryMovementEntity.Type.RESTOCK, "-5", "Alasan", "k1")));
        assertThrows(InvalidOperationException.class, () -> service.recordMovement(cashier, outletId, itemId,
                movementRequest(InventoryMovementEntity.Type.WASTE, "-5", "Alasan", "k2")));

        verifyNoInteractions(inventory);
    }

    // ---------------------------------------------------------------- 7. reason rules

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"   "})
    void blankOrNullRestockReasonDefaultsToRestock(String reason) {
        InventoryItemEntity item = item(itemId, "Susu", "ml", "100");
        stubNewMovementPath(InventoryMovementEntity.Type.RESTOCK, cashier, item, balanceOf(item, "10"));

        service.recordMovement(cashier, outletId, itemId,
                movementRequest(InventoryMovementEntity.Type.RESTOCK, "5", reason, "k1"));

        assertEquals("Restock", persistedMovement().getReason());
    }

    @Test
    void blankWasteReasonIsRejected() {
        assertThrows(InvalidOperationException.class, () -> service.recordMovement(cashier, outletId, itemId,
                movementRequest(InventoryMovementEntity.Type.WASTE, "1", "  ", "k1")));
        assertThrows(InvalidOperationException.class, () -> service.recordMovement(cashier, outletId, itemId,
                movementRequest(InventoryMovementEntity.Type.WASTE, "1", null, "k2")));

        verifyNoInteractions(inventory);
    }

    @Test
    void blankAdjustmentReasonIsRejected() {
        assertThrows(InvalidOperationException.class, () -> service.recordMovement(owner, outletId, itemId,
                movementRequest(InventoryMovementEntity.Type.ADJUSTMENT, "2", "   ", "k1")));
        assertThrows(InvalidOperationException.class, () -> service.recordMovement(owner, outletId, itemId,
                movementRequest(InventoryMovementEntity.Type.ADJUSTMENT, "2", null, "k2")));

        verifyNoInteractions(inventory);
    }

    @Test
    void reasonIsTrimmedBeforeBeingStored() {
        InventoryItemEntity item = item(itemId, "Susu", "ml", "100");
        stubNewMovementPath(InventoryMovementEntity.Type.WASTE, cashier, item, balanceOf(item, "10"));

        service.recordMovement(cashier, outletId, itemId,
                movementRequest(InventoryMovementEntity.Type.WASTE, "1", "  Susu basi  ", "k1"));

        assertEquals("Susu basi", persistedMovement().getReason());
    }

    // ---------------------------------------------------------------- 8. idempotency

    @Test
    void sameKeyAndSameFingerprintReplaysExistingMovementWithoutSideEffects() {
        InventoryItemEntity item = item(itemId, "Susu", "ml", "100");
        InventoryMovementEntity existing = existingMovement(item, InventoryMovementEntity.Type.WASTE, "-5",
                "Basi", "k-replay", "fp(WASTE|" + itemId + "|-5|Basi)");
        when(guard.requireOutlet(cashier, outletId)).thenReturn(context);
        when(inventory.item(tenantId, outletId, itemId)).thenReturn(Optional.of(item));
        when(inventory.movementByKey(tenantId, "k-replay")).thenReturn(Optional.of(existing));
        stubDigest();

        FnbStockMovementResponse response = service.recordMovement(cashier, outletId, itemId,
                movementRequest(InventoryMovementEntity.Type.WASTE, "5", "Basi", "k-replay"));

        assertEquals(existing.getId(), response.getId());
        assertEquals(InventoryMovementEntity.Type.WASTE, response.getType());
        assertEquals(0, new BigDecimal("-5").compareTo(response.getQuantityDelta()));
        verify(inventory, never()).persist(any(InventoryMovementEntity.class));
        verify(inventory, never()).persist(any(InventoryBalanceEntity.class));
        verify(inventory, never()).lock(any(InventoryBalanceEntity.class));
        verify(inventory, never()).balance(any(UUID.class), any(UUID.class), any(UUID.class));
        verify(inventory, never()).flush();
    }

    @Test
    void sameKeyWithDifferentFingerprintIsAConflictAndChangesNothing() {
        InventoryItemEntity item = item(itemId, "Susu", "ml", "100");
        InventoryMovementEntity existing = existingMovement(item, InventoryMovementEntity.Type.WASTE, "-5",
                "Basi", "k-conflict", "fp(WASTE|" + itemId + "|-5|Basi)");
        when(guard.requireOutlet(cashier, outletId)).thenReturn(context);
        when(inventory.item(tenantId, outletId, itemId)).thenReturn(Optional.of(item));
        when(inventory.movementByKey(tenantId, "k-conflict")).thenReturn(Optional.of(existing));
        stubDigest();

        assertThrows(OperationConflictException.class, () -> service.recordMovement(cashier, outletId, itemId,
                movementRequest(InventoryMovementEntity.Type.WASTE, "6", "Basi", "k-conflict")));

        verify(inventory, never()).persist(any(InventoryMovementEntity.class));
        verify(inventory, never()).persist(any(InventoryBalanceEntity.class));
        verify(inventory, never()).lock(any(InventoryBalanceEntity.class));
        verify(inventory, never()).flush();
    }

    @Test
    void whitespaceOnlyReasonAndScaleDifferencesStillReplay() {
        InventoryItemEntity item = item(itemId, "Susu", "ml", "100");
        InventoryMovementEntity existing = existingMovement(item, InventoryMovementEntity.Type.WASTE, "-5",
                "Basi", "k-ws", "fp(WASTE|" + itemId + "|-5|Basi)");
        when(guard.requireOutlet(cashier, outletId)).thenReturn(context);
        when(inventory.item(tenantId, outletId, itemId)).thenReturn(Optional.of(item));
        when(inventory.movementByKey(tenantId, "k-ws")).thenReturn(Optional.of(existing));
        stubDigest();

        FnbStockMovementResponse response = service.recordMovement(cashier, outletId, itemId,
                movementRequest(InventoryMovementEntity.Type.WASTE, "5.000", "  Basi  ", "k-ws"));

        assertEquals(existing.getId(), response.getId());
        verify(inventory, never()).persist(any(InventoryMovementEntity.class));
        verify(inventory, never()).lock(any(InventoryBalanceEntity.class));
    }

    // ---------------------------------------------------------------- 9. new movement

    @Test
    void newMovementLocksBalanceBeforeApplyingDeltaThenPersistsAndFlushes() {
        InventoryItemEntity item = item(itemId, "Susu", "ml", "100");
        InventoryBalanceEntity balance = balanceOf(item, "10");
        stubNewMovementPath(InventoryMovementEntity.Type.WASTE, cashier, item, balance);
        BigDecimal[] quantityAtLock = new BigDecimal[1];
        doAnswer(invocation -> {
            quantityAtLock[0] = balance.getQuantityOnHand();
            return null;
        }).when(inventory).lock(balance);

        FnbStockMovementResponse response = service.recordMovement(cashier, outletId, itemId,
                movementRequest(InventoryMovementEntity.Type.WASTE, "4", "Jatuh", "k-new"));

        InOrder order = inOrder(inventory);
        order.verify(inventory).lock(balance);
        order.verify(inventory).persist(any(InventoryMovementEntity.class));
        order.verify(inventory).flush();
        assertEquals(0, new BigDecimal("10").compareTo(quantityAtLock[0]));
        assertEquals(0, new BigDecimal("6").compareTo(balance.getQuantityOnHand()));
        verify(inventory, never()).persist(any(InventoryBalanceEntity.class));

        InventoryMovementEntity movement = persistedMovement();
        assertEquals(membershipId, movement.getActorMembershipId());
        assertEquals(InventoryMovementEntity.Type.WASTE, movement.getType());
        assertEquals("k-new", movement.getIdempotencyKey());
        assertEquals("fp(WASTE|" + itemId + "|-4|Jatuh)", movement.getRequestFingerprint());
        assertEquals(tenantId, movement.getTenantId());
        assertEquals(outletId, movement.getOutletId());
        assertSame(item, movement.getInventoryItem());
        assertNull(movement.getOrderId());
        assertEquals(itemId, response.getInventoryItemId());
        assertEquals(membershipId, response.getActorMembershipId());
        assertEquals(0, new BigDecimal("-4").compareTo(response.getQuantityDelta()));
    }

    @Test
    void missingBalanceRowIsCreatedAndPersistedInsteadOfLocked() {
        InventoryItemEntity item = item(itemId, "Susu", "ml", "100");
        stubNewMovementPath(InventoryMovementEntity.Type.RESTOCK, cashier, item, null);

        service.recordMovement(cashier, outletId, itemId,
                movementRequest(InventoryMovementEntity.Type.RESTOCK, "7", "Kulakan", "k-nobalance"));

        ArgumentCaptor<InventoryBalanceEntity> balanceCaptor = ArgumentCaptor.forClass(InventoryBalanceEntity.class);
        verify(inventory).persist(balanceCaptor.capture());
        verify(inventory, never()).lock(any(InventoryBalanceEntity.class));
        InventoryBalanceEntity balance = balanceCaptor.getValue();
        assertEquals(tenantId, balance.getTenantId());
        assertEquals(outletId, balance.getOutletId());
        assertSame(item, balance.getInventoryItem());
        assertEquals(0, new BigDecimal("7").compareTo(balance.getQuantityOnHand()));
        assertEquals(0, new BigDecimal("7").compareTo(persistedMovement().getQuantityDelta()));
        verify(inventory).flush();
    }

    @Test
    void movementForUnknownItemIsNotFoundAndTouchesNoBalance() {
        when(guard.requireOutlet(cashier, outletId)).thenReturn(context);
        when(inventory.item(tenantId, outletId, itemId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.recordMovement(cashier, outletId, itemId,
                movementRequest(InventoryMovementEntity.Type.RESTOCK, "7", null, "k1")));

        verify(inventory, never()).lock(any(InventoryBalanceEntity.class));
        verify(inventory, never()).persist(any(InventoryMovementEntity.class));
    }

    // ---------------------------------------------------------------- 10. movements

    @Test
    void movementsRejectOutOfRangePagingBeforeAnyGuardOrQuery() {
        assertThrows(InvalidOperationException.class, () -> service.movements(cashier, outletId, itemId, -1, 10));
        assertThrows(InvalidOperationException.class, () -> service.movements(cashier, outletId, itemId, 0, 0));
        assertThrows(InvalidOperationException.class, () -> service.movements(cashier, outletId, itemId, 0, 51));
        assertThrows(InvalidOperationException.class, () -> service.movements(cashier, outletId, itemId, 1_000_001, 10));

        verifyNoInteractions(guard, inventory);
    }

    @Test
    void movementsAcceptBoundaryPagingValues() {
        InventoryItemEntity item = item(itemId, "Susu", "ml", "100");
        when(guard.requireOutlet(cashier, outletId)).thenReturn(context);
        when(inventory.item(tenantId, outletId, itemId)).thenReturn(Optional.of(item));
        when(inventory.movements(tenantId, outletId, itemId, 0, 1)).thenReturn(List.of());
        when(inventory.movements(tenantId, outletId, itemId, 1_000_000, 50)).thenReturn(List.of());

        assertTrue(service.movements(cashier, outletId, itemId, 0, 1).isEmpty());
        assertTrue(service.movements(cashier, outletId, itemId, 1_000_000, 50).isEmpty());

        verify(guard, times(2)).requireOutlet(cashier, outletId);
    }

    @Test
    void movementsOfUnknownItemAreNotFoundWithoutQueryingHistory() {
        when(guard.requireOutlet(cashier, outletId)).thenReturn(context);
        when(inventory.item(tenantId, outletId, itemId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.movements(cashier, outletId, itemId, 0, 10));

        verify(guard).requireOutlet(cashier, outletId);
        verify(inventory, never()).movements(any(UUID.class), any(UUID.class), any(UUID.class),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void movementsPreserveRepositoryOrderAndMapEveryField() {
        InventoryItemEntity item = item(itemId, "Susu", "ml", "100");
        UUID orderId = UUID.randomUUID();
        InventoryMovementEntity sale = InventoryMovementEntity.record(tenantId, outletId, item, orderId,
                InventoryMovementEntity.Type.SALE, new BigDecimal("-120"), "Penjualan ORD-1", "sale:" + orderId + ":" + itemId,
                "fp", membershipId);
        InventoryMovementEntity restock = existingMovement(item, InventoryMovementEntity.Type.RESTOCK, "500",
                "Stok awal", "init:" + itemId, "fp2");
        ReflectionTestUtils.setField(sale, "id", UUID.randomUUID());
        when(guard.requireOutlet(cashier, outletId)).thenReturn(context);
        when(inventory.item(tenantId, outletId, itemId)).thenReturn(Optional.of(item));
        when(inventory.movements(tenantId, outletId, itemId, 2, 20)).thenReturn(List.of(sale, restock));

        List<FnbStockMovementResponse> responses = service.movements(cashier, outletId, itemId, 2, 20);

        assertEquals(2, responses.size());
        FnbStockMovementResponse first = responses.get(0);
        assertEquals(sale.getId(), first.getId());
        assertEquals(itemId, first.getInventoryItemId());
        assertEquals(InventoryMovementEntity.Type.SALE, first.getType());
        assertEquals(0, new BigDecimal("-120").compareTo(first.getQuantityDelta()));
        assertEquals("Penjualan ORD-1", first.getReason());
        assertEquals(orderId, first.getOrderId());
        assertEquals(sale.getOccurredAt(), first.getOccurredAt());
        assertEquals(membershipId, first.getActorMembershipId());
        FnbStockMovementResponse second = responses.get(1);
        assertEquals(restock.getId(), second.getId());
        assertEquals(InventoryMovementEntity.Type.RESTOCK, second.getType());
        assertNull(second.getOrderId());
    }
}
