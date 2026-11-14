package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.FnbStockCountLineRequest;
import org.lite.project.cashiersaas.dto.request.FnbStockCountRequest;
import org.lite.project.cashiersaas.dto.request.FnbStockMovementRequest;
import org.lite.project.cashiersaas.dto.response.FnbInventoryItemResponse;
import org.lite.project.cashiersaas.dto.response.FnbStockCountLineResponse;
import org.lite.project.cashiersaas.dto.response.FnbStockCountResponse;
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
import org.lite.project.cashiersaas.service.DigestService;
import org.lite.project.cashiersaas.service.FnbInventoryService;
import org.lite.project.cashiersaas.service.MerchantAccessGuard;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** M6 (TASK-079): purchase price / moving average, cost visibility and the batch stock count. */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class FnbInventoryCostAndStockCountTest {
    @Mock private MerchantAccessGuard guard;
    @Mock private FnbInventoryRepository inventory;
    @Mock private DigestService tokens;
    @InjectMocks private FnbInventoryService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID outletId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    // Ascending ids so the expected processing order of a stock count is obvious.
    private final UUID idA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private final UUID idB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private final UUID idC = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private final UUID ownerMembershipId = UUID.randomUUID();
    private final UUID cashierMembershipId = UUID.randomUUID();
    private final TenantPrincipal owner = new TenantPrincipal(UUID.randomUUID(), tenantId, outletId,
            ownerMembershipId, UUID.randomUUID(), Set.of(Role.OWNERMERCHANT));
    private final TenantPrincipal cashier = new TenantPrincipal(UUID.randomUUID(), tenantId, outletId,
            cashierMembershipId, UUID.randomUUID(), Set.of(Role.CASHIER));
    private final TenantEntity tenant = new TenantEntity();
    private final OutletEntity outlet = OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null);
    private final MerchantAccessContext ownerContext = new MerchantAccessContext(tenant, outlet,
            withId(TenantMembershipEntity.grantOwner(null, tenant), ownerMembershipId));
    private final MerchantAccessContext cashierContext = new MerchantAccessContext(tenant, outlet,
            withId(TenantMembershipEntity.grantCashier(null, tenant), cashierMembershipId));

    // ---------------------------------------------------------------- restock price and moving average

    @ParameterizedTest
    @CsvSource({"false,7.5000,6.2500,7.5", "true,7,6.0000,7"})
    void pricedRestockBlendsTheAverageRecordsTheEnteredPriceAndShowsTheCostToOwnerOnly(
            boolean asOwner, String price, String expectedAverage, String plainPrice) {
        TenantPrincipal principal = asOwner ? owner : cashier;
        InventoryItemEntity item = item(itemId, "Susu");
        InventoryBalanceEntity balance = balance(item, "10", "5");
        stubMovementPath(principal, asOwner ? ownerContext : cashierContext, item, balance);

        FnbStockMovementResponse response = service.recordMovement(principal, outletId, itemId,
                movement(InventoryMovementEntity.Type.RESTOCK, "10", price, "Kulakan", "k1"));

        // (10*5 + 10*price) / 20, e.g. 6.25 for 7.5
        assertEquals(new BigDecimal(expectedAverage), balance.getAverageUnitCost());
        assertDecimal("20", balance.getQuantityOnHand());
        InventoryMovementEntity saved = persistedMovement();
        assertDecimal(plainPrice, saved.getUnitCost());
        assertEquals(InventoryMovementEntity.Type.RESTOCK, saved.getType());
        assertDecimal("10", saved.getQuantityDelta());
        // trailing zeros of the price are stripped inside the fingerprint
        assertEquals("fp(RESTOCK|" + itemId + "|10|Kulakan|" + plainPrice + ")", saved.getRequestFingerprint());
        if (asOwner) {
            assertDecimal(plainPrice, response.getUnitCost());
        } else {
            assertNull(response.getUnitCost(), "a cashier may send a price but never sees costs");
        }
    }

    @Test
    void restockWithoutPriceKeepsTheAverageRecordsItOnTheMovementAndKeepsTheOldFingerprint() {
        InventoryItemEntity item = item(itemId, "Susu");
        InventoryBalanceEntity balance = balance(item, "10", "5");
        stubMovementPath(owner, ownerContext, item, balance);

        FnbStockMovementResponse response = service.recordMovement(owner, outletId, itemId,
                movement(InventoryMovementEntity.Type.RESTOCK, "10", null, "Kulakan", "k1"));

        assertEquals(new BigDecimal("5.0000"), balance.getAverageUnitCost());
        assertDecimal("20", balance.getQuantityOnHand());
        InventoryMovementEntity saved = persistedMovement();
        // accepted deviation: no price entered -> the balance average is recorded as the movement unit cost
        assertEquals(new BigDecimal("5.0000"), saved.getUnitCost());
        assertEquals("fp(RESTOCK|" + itemId + "|10|Kulakan)", saved.getRequestFingerprint());
        assertDecimal("5", response.getUnitCost());
    }

    @Test
    void restockWithoutPriceOnABalanceWithoutAverageRecordsNullCost() {
        InventoryItemEntity item = item(itemId, "Susu");
        InventoryBalanceEntity balance = balanceOf(item, "10");
        stubMovementPath(owner, ownerContext, item, balance);

        service.recordMovement(owner, outletId, itemId,
                movement(InventoryMovementEntity.Type.RESTOCK, "10", null, null, "k1"));

        assertNull(balance.getAverageUnitCost());
        assertNull(persistedMovement().getUnitCost());
    }

    @Test
    void firstPricedRestockOnAMissingBalanceStartsTheAverageAtThePrice() {
        InventoryItemEntity item = item(itemId, "Susu");
        stubMovementPath(owner, ownerContext, item, null);

        service.recordMovement(owner, outletId, itemId,
                movement(InventoryMovementEntity.Type.RESTOCK, "4", "12000", "Kulakan", "k1"));

        ArgumentCaptor<InventoryBalanceEntity> captor = ArgumentCaptor.forClass(InventoryBalanceEntity.class);
        verify(inventory).persist(captor.capture());
        assertEquals(new BigDecimal("12000.0000"), captor.getValue().getAverageUnitCost());
        assertDecimal("4", captor.getValue().getQuantityOnHand());
    }

    @Test
    void wasteAndAdjustmentRecordTheAverageBeforeTheChangeAndNeverMoveIt() {
        InventoryItemEntity item = item(itemId, "Susu");
        InventoryBalanceEntity balance = balance(item, "10", "5");
        when(guard.requireOutlet(cashier, outletId)).thenReturn(cashierContext);
        when(guard.requireOwner(owner, outletId)).thenReturn(ownerContext);
        when(inventory.item(tenantId, outletId, itemId)).thenReturn(Optional.of(item));
        when(inventory.balance(tenantId, outletId, itemId)).thenReturn(Optional.of(balance));
        when(tokens.digest(anyString())).thenAnswer(invocation -> "fp(" + invocation.getArgument(0) + ")");

        service.recordMovement(cashier, outletId, itemId,
                movement(InventoryMovementEntity.Type.WASTE, "2", null, "Jatuh", "k-waste"));
        service.recordMovement(owner, outletId, itemId,
                movement(InventoryMovementEntity.Type.ADJUSTMENT, "-3", null, "Koreksi", "k-adjust"));

        ArgumentCaptor<InventoryMovementEntity> captor = ArgumentCaptor.forClass(InventoryMovementEntity.class);
        verify(inventory, times(2)).persist(captor.capture());
        assertEquals(InventoryMovementEntity.Type.WASTE, captor.getAllValues().get(0).getType());
        assertEquals(new BigDecimal("5.0000"), captor.getAllValues().get(0).getUnitCost());
        assertEquals(InventoryMovementEntity.Type.ADJUSTMENT, captor.getAllValues().get(1).getType());
        assertEquals(new BigDecimal("5.0000"), captor.getAllValues().get(1).getUnitCost());
        assertEquals(new BigDecimal("5.0000"), balance.getAverageUnitCost());
        assertDecimal("5", balance.getQuantityOnHand());
    }

    @Test
    void unitCostIsRejectedOnWasteAndAdjustmentBeforeAnyStockLookup() {
        when(guard.requireOutlet(cashier, outletId)).thenReturn(cashierContext);
        when(guard.requireOwner(owner, outletId)).thenReturn(ownerContext);

        InvalidOperationException waste = assertThrows(InvalidOperationException.class, () ->
                service.recordMovement(cashier, outletId, itemId,
                        movement(InventoryMovementEntity.Type.WASTE, "2", "5", "Jatuh", "k1")));
        InvalidOperationException adjustment = assertThrows(InvalidOperationException.class, () ->
                service.recordMovement(owner, outletId, itemId,
                        movement(InventoryMovementEntity.Type.ADJUSTMENT, "2", "5", "Koreksi", "k2")));

        assertEquals("Harga beli hanya untuk restock.", waste.getMessage());
        assertEquals("Harga beli hanya untuk restock.", adjustment.getMessage());
        verifyNoInteractions(inventory);
    }

    @Test
    void replayComparesThePriceInTheFingerprintAndConflictsWhenItDiffers() {
        InventoryItemEntity item = item(itemId, "Susu");
        InventoryMovementEntity existing = existingMovement(item, "10", "Kulakan", "k-price",
                "fp(RESTOCK|" + itemId + "|10|Kulakan|7)", "7");
        when(guard.requireOutlet(owner, outletId)).thenReturn(ownerContext);
        when(inventory.item(tenantId, outletId, itemId)).thenReturn(Optional.of(item));
        when(inventory.movementByKey(tenantId, "k-price")).thenReturn(Optional.of(existing));
        when(tokens.digest(anyString())).thenAnswer(invocation -> "fp(" + invocation.getArgument(0) + ")");

        FnbStockMovementResponse replay = service.recordMovement(owner, outletId, itemId,
                movement(InventoryMovementEntity.Type.RESTOCK, "10", "7.000", "Kulakan", "k-price"));
        assertEquals(existing.getId(), replay.getId());
        assertDecimal("7", replay.getUnitCost());

        assertThrows(OperationConflictException.class, () -> service.recordMovement(owner, outletId, itemId,
                movement(InventoryMovementEntity.Type.RESTOCK, "10", "8", "Kulakan", "k-price")));
        assertThrows(OperationConflictException.class, () -> service.recordMovement(owner, outletId, itemId,
                movement(InventoryMovementEntity.Type.RESTOCK, "10", null, "Kulakan", "k-price")));
        verify(inventory, never()).persist(any(InventoryMovementEntity.class));
        verify(inventory, never()).lock(any(InventoryBalanceEntity.class));
    }

    @Test
    void replayHidesTheCostFromACashier() {
        InventoryItemEntity item = item(itemId, "Susu");
        InventoryMovementEntity existing = existingMovement(item, "10", "Kulakan", "k-hide",
                "fp(RESTOCK|" + itemId + "|10|Kulakan|7)", "7");
        when(guard.requireOutlet(cashier, outletId)).thenReturn(cashierContext);
        when(inventory.item(tenantId, outletId, itemId)).thenReturn(Optional.of(item));
        when(inventory.movementByKey(tenantId, "k-hide")).thenReturn(Optional.of(existing));
        when(tokens.digest(anyString())).thenAnswer(invocation -> "fp(" + invocation.getArgument(0) + ")");

        FnbStockMovementResponse replay = service.recordMovement(cashier, outletId, itemId,
                movement(InventoryMovementEntity.Type.RESTOCK, "10", "7", "Kulakan", "k-hide"));

        assertEquals(existing.getId(), replay.getId());
        assertNull(replay.getUnitCost());
    }

    // ---------------------------------------------------------------- cost visibility on reads

    @Test
    void itemListShowsAverageCostToOwnerOnlyAndNullWhenNeverPriced() {
        InventoryItemEntity priced = item(idA, "Susu");
        InventoryItemEntity unpriced = item(idB, "Gula");
        when(inventory.items(tenantId, outletId)).thenReturn(List.of(priced, unpriced));
        when(inventory.balances(tenantId, outletId, List.of(idA, idB)))
                .thenReturn(List.of(balance(priced, "10", "4.5"), balanceOf(unpriced, "10")));
        when(guard.requireOutlet(owner, outletId)).thenReturn(ownerContext);
        when(guard.requireOutlet(cashier, outletId)).thenReturn(cashierContext);

        List<FnbInventoryItemResponse> asOwner = service.items(owner, outletId, null);
        List<FnbInventoryItemResponse> asCashier = service.items(cashier, outletId, null);

        assertDecimal("4.5", asOwner.get(0).getAverageUnitCost());
        assertNull(asOwner.get(1).getAverageUnitCost());
        assertNull(asCashier.get(0).getAverageUnitCost());
        assertNull(asCashier.get(1).getAverageUnitCost());
        assertDecimal("10", asCashier.get(0).getQuantityOnHand());
    }

    @Test
    void movementHistoryShowsUnitCostToOwnerOnlyAndDeactivateShowsAverageToOwner() {
        InventoryItemEntity item = item(itemId, "Susu");
        InventoryMovementEntity history = existingMovement(item, "10", "Kulakan", "k1", "fp", "7");
        when(guard.requireOutlet(owner, outletId)).thenReturn(ownerContext);
        when(guard.requireOutlet(cashier, outletId)).thenReturn(cashierContext);
        when(guard.requireOwner(owner, outletId)).thenReturn(ownerContext);
        when(inventory.item(tenantId, outletId, itemId)).thenReturn(Optional.of(item));
        when(inventory.movements(tenantId, outletId, itemId, 0, 10)).thenReturn(List.of(history));
        when(inventory.balance(tenantId, outletId, itemId)).thenReturn(Optional.of(balance(item, "10", "3")));

        assertDecimal("7", service.movements(owner, outletId, itemId, 0, 10).get(0).getUnitCost());
        assertNull(service.movements(cashier, outletId, itemId, 0, 10).get(0).getUnitCost());
        assertDecimal("3", service.deactivateItem(owner, outletId, itemId).getAverageUnitCost());
    }

    // ---------------------------------------------------------------- stock count

    @ParameterizedTest
    @CsvSource({"8.000,-2,8", "15,5,15"})
    void stockCountDeficitOrSurplusRecordsAnAdjustmentWithReasonKeyFingerprintAndAverageBeforeTheChange(
            String counted, String expectedDifference, String expectedCounted) {
        InventoryItemEntity item = item(idA, "Susu");
        InventoryBalanceEntity balance = balance(item, "10", "3");
        stubCountItem(item, balance);
        stubDigest();
        stubMovementIds();
        when(guard.requireOwner(owner, outletId)).thenReturn(ownerContext);

        FnbStockCountResponse response = service.stockCount(owner, outletId, count("k1", null, line(idA, counted)));

        InventoryMovementEntity saved = persistedMovement();
        assertEquals(InventoryMovementEntity.Type.ADJUSTMENT, saved.getType());
        assertDecimal(expectedDifference, saved.getQuantityDelta());
        assertEquals("Stok opname", saved.getReason());
        assertEquals("count:fp(k1|" + idA + ")", saved.getIdempotencyKey());
        assertEquals("fp(" + idA + "=" + expectedCounted + "|)", saved.getRequestFingerprint());
        assertEquals(new BigDecimal("3.0000"), saved.getUnitCost());
        assertEquals(ownerMembershipId, saved.getActorMembershipId());
        assertNull(saved.getOrderId());
        assertDecimal(expectedCounted, balance.getQuantityOnHand());
        // a count never moves the average cost
        assertEquals(new BigDecimal("3.0000"), balance.getAverageUnitCost());
        FnbStockCountLineResponse result = response.getLines().get(0);
        assertEquals(idA, result.getInventoryItemId());
        assertEquals("Susu", result.getName());
        assertEquals("ml", result.getUnit());
        assertDecimal("10", result.getPreviousQuantity());
        assertDecimal(expectedCounted, result.getCountedQuantity());
        assertDecimal(expectedDifference, result.getDifference());
        assertEquals(saved.getId(), result.getMovementId());
        assertNotNull(result.getMovementId());
    }

    @Test
    void stockCountNoteIsTrimmedIntoTheReasonAndTheFingerprintAndBlankNoteMeansNoNote() {
        InventoryItemEntity withNote = item(idA, "Susu");
        stubCountItem(withNote, balanceOf(withNote, "10"));
        stubDigest();
        stubMovementIds();
        when(guard.requireOwner(owner, outletId)).thenReturn(ownerContext);

        service.stockCount(owner, outletId, count("k1", "  Akhir bulan  ", line(idA, "9")));
        service.stockCount(owner, outletId, count("k2", "   ", line(idA, "8")));

        ArgumentCaptor<InventoryMovementEntity> captor = ArgumentCaptor.forClass(InventoryMovementEntity.class);
        verify(inventory, times(2)).persist(captor.capture());
        assertEquals("Stok opname: Akhir bulan", captor.getAllValues().get(0).getReason());
        assertEquals("fp(" + idA + "=9|Akhir bulan)", captor.getAllValues().get(0).getRequestFingerprint());
        assertEquals("Stok opname", captor.getAllValues().get(1).getReason());
        assertEquals("fp(" + idA + "=8|)", captor.getAllValues().get(1).getRequestFingerprint());
    }

    @Test
    void stockCountZeroDifferenceWritesNoMovementButStillReportsTheLine() {
        InventoryItemEntity same = item(idA, "Susu");
        InventoryItemEntity changed = item(idB, "Gula");
        InventoryBalanceEntity sameBalance = balance(same, "10", "3");
        InventoryBalanceEntity changedBalance = balance(changed, "4", "2");
        stubCountItem(same, sameBalance);
        stubCountItem(changed, changedBalance);
        stubDigest();
        stubMovementIds();
        when(guard.requireOwner(owner, outletId)).thenReturn(ownerContext);

        FnbStockCountResponse response = service.stockCount(owner, outletId,
                count("k1", null, line(idA, "10.000"), line(idB, "6")));

        ArgumentCaptor<InventoryMovementEntity> captor = ArgumentCaptor.forClass(InventoryMovementEntity.class);
        verify(inventory, times(1)).persist(captor.capture());
        assertSame(changed, captor.getValue().getInventoryItem());
        verify(inventory).lock(sameBalance);
        FnbStockCountLineResponse zero = response.getLines().get(0);
        assertEquals(idA, zero.getInventoryItemId());
        assertDecimal("10", zero.getPreviousQuantity());
        assertDecimal("0", zero.getDifference());
        assertNull(zero.getMovementId());
        assertDecimal("10", sameBalance.getQuantityOnHand());
        assertNotNull(response.getLines().get(1).getMovementId());
    }

    @Test
    void stockCountCreatesAMissingBalanceAndRecordsNoCostForIt() {
        InventoryItemEntity item = item(idA, "Susu");
        stubCountItem(item, null);
        stubDigest();
        // no stubMovementIds(): its persist(Movement) stub would make strict stubs reject the persist(Balance) call
        when(guard.requireOwner(owner, outletId)).thenReturn(ownerContext);

        FnbStockCountResponse response = service.stockCount(owner, outletId, count("k1", null, line(idA, "5")));

        ArgumentCaptor<InventoryBalanceEntity> balanceCaptor = ArgumentCaptor.forClass(InventoryBalanceEntity.class);
        verify(inventory).persist(balanceCaptor.capture());
        verify(inventory, never()).lock(any(InventoryBalanceEntity.class));
        assertDecimal("5", balanceCaptor.getValue().getQuantityOnHand());
        assertNull(persistedMovement().getUnitCost());
        assertDecimal("0", response.getLines().get(0).getPreviousQuantity());
        assertDecimal("5", response.getLines().get(0).getDifference());
    }

    @Test
    void stockCountProcessesAndReturnsLinesInAscendingItemIdWhateverTheRequestOrder() {
        InventoryItemEntity a = item(idA, "A");
        InventoryItemEntity b = item(idB, "B");
        InventoryItemEntity c = item(idC, "C");
        InventoryBalanceEntity balanceA = balanceOf(a, "1");
        InventoryBalanceEntity balanceB = balanceOf(b, "1");
        InventoryBalanceEntity balanceC = balanceOf(c, "1");
        stubCountItem(a, balanceA);
        stubCountItem(b, balanceB);
        stubCountItem(c, balanceC);
        stubDigest();
        stubMovementIds();
        when(guard.requireOwner(owner, outletId)).thenReturn(ownerContext);

        FnbStockCountResponse response = service.stockCount(owner, outletId,
                count("k1", null, line(idC, "3"), line(idA, "2"), line(idB, "4")));

        InOrder order = inOrder(inventory);
        order.verify(inventory).lock(balanceA);
        order.verify(inventory).persist(any(InventoryMovementEntity.class));
        order.verify(inventory).lock(balanceB);
        order.verify(inventory).persist(any(InventoryMovementEntity.class));
        order.verify(inventory).lock(balanceC);
        order.verify(inventory).persist(any(InventoryMovementEntity.class));
        assertEquals(List.of(idA, idB, idC), response.getLines().stream().map(FnbStockCountLineResponse::getInventoryItemId).toList());
        // the fingerprint is built from the sorted lines, so the request order does not matter
        ArgumentCaptor<InventoryMovementEntity> captor = ArgumentCaptor.forClass(InventoryMovementEntity.class);
        verify(inventory, times(3)).persist(captor.capture());
        assertEquals("fp(" + idA + "=2;" + idB + "=4;" + idC + "=3|)", captor.getAllValues().get(0).getRequestFingerprint());
    }

    @Test
    void stockCountRejectsDuplicateItemsBeforeAnyLookup() {
        when(guard.requireOwner(owner, outletId)).thenReturn(ownerContext);

        InvalidOperationException error = assertThrows(InvalidOperationException.class, () ->
                service.stockCount(owner, outletId, count("k1", null, line(idA, "1"), line(idA, "2"))));

        assertEquals("Bahan duplikat dalam stok opname.", error.getMessage());
        verifyNoInteractions(inventory);
        verifyNoInteractions(tokens);
    }

    @Test
    void stockCountWithUnknownOrInactiveItemIsNotFoundAndChangesNothing() {
        InventoryItemEntity a = item(idA, "A");
        when(guard.requireOwner(owner, outletId)).thenReturn(ownerContext);
        when(inventory.item(tenantId, outletId, idA)).thenReturn(Optional.of(a));
        when(inventory.item(tenantId, outletId, idB)).thenReturn(Optional.empty());
        stubDigest();

        assertThrows(ResourceNotFoundException.class, () ->
                service.stockCount(owner, outletId, count("k1", null, line(idA, "1"), line(idB, "2"))));

        verify(inventory, never()).persist(any(InventoryMovementEntity.class));
        verify(inventory, never()).persist(any(InventoryBalanceEntity.class));
        verify(inventory, never()).lock(any(InventoryBalanceEntity.class));
    }

    @Test
    void stockCountIsOwnerOnly() {
        when(guard.requireOwner(cashier, outletId)).thenThrow(new ResourceNotFoundException());

        assertThrows(ResourceNotFoundException.class, () ->
                service.stockCount(cashier, outletId, count("k1", null, line(idA, "1"))));

        verify(guard, never()).requireOutlet(any(TenantPrincipal.class), any(UUID.class));
        verifyNoInteractions(inventory);
    }

    @Test
    void stockCountReplayReturnsThePreviousResultWithoutTouchingStock() {
        InventoryItemEntity a = item(idA, "A");
        InventoryItemEntity b = item(idB, "B");
        // First run: A had 10 and was counted as 8 (movement -2); B was already 7 (no movement).
        InventoryMovementEntity first = countMovement(a, "-2", "k1", "fp(" + idA + "=8;" + idB + "=7|Akhir bulan)");
        when(guard.requireOwner(owner, outletId)).thenReturn(ownerContext);
        when(inventory.item(tenantId, outletId, idA)).thenReturn(Optional.of(a));
        when(inventory.item(tenantId, outletId, idB)).thenReturn(Optional.of(b));
        when(inventory.movementByKey(tenantId, "count:fp(k1|" + idA + ")")).thenReturn(Optional.of(first));
        stubDigest();

        FnbStockCountResponse response = service.stockCount(owner, outletId,
                count("k1", "Akhir bulan", line(idB, "7"), line(idA, "8")));

        FnbStockCountLineResponse lineA = response.getLines().get(0);
        assertDecimal("10", lineA.getPreviousQuantity());
        assertDecimal("8", lineA.getCountedQuantity());
        assertDecimal("-2", lineA.getDifference());
        assertEquals(first.getId(), lineA.getMovementId());
        FnbStockCountLineResponse lineB = response.getLines().get(1);
        assertDecimal("7", lineB.getPreviousQuantity());
        assertDecimal("0", lineB.getDifference());
        assertNull(lineB.getMovementId());
        verify(inventory, never()).balance(any(UUID.class), any(UUID.class), any(UUID.class));
        verify(inventory, never()).lock(any(InventoryBalanceEntity.class));
        verify(inventory, never()).persist(any(InventoryMovementEntity.class));
        verify(inventory, never()).persist(any(InventoryBalanceEntity.class));
    }

    @Test
    void stockCountSameKeyWithDifferentContentIsAConflictAndChangesNothing() {
        InventoryItemEntity a = item(idA, "A");
        InventoryMovementEntity first = countMovement(a, "-2", "k1", "fp(" + idA + "=8|)");
        when(guard.requireOwner(owner, outletId)).thenReturn(ownerContext);
        when(inventory.item(tenantId, outletId, idA)).thenReturn(Optional.of(a));
        when(inventory.movementByKey(tenantId, "count:fp(k1|" + idA + ")")).thenReturn(Optional.of(first));
        stubDigest();

        OperationConflictException error = assertThrows(OperationConflictException.class, () ->
                service.stockCount(owner, outletId, count("k1", null, line(idA, "9"))));

        assertEquals("Kunci idempotensi telah digunakan.", error.getMessage());
        verify(inventory, never()).persist(any(InventoryMovementEntity.class));
        verify(inventory, never()).persist(any(InventoryBalanceEntity.class));
        verify(inventory, never()).lock(any(InventoryBalanceEntity.class));
    }

    // ---------------------------------------------------------------- helpers

    private InventoryItemEntity item(UUID id, String name) {
        return withId(InventoryItemEntity.create(tenant, outlet, null, name, "ml", new BigDecimal("1")), id);
    }

    private InventoryBalanceEntity balanceOf(InventoryItemEntity item, String quantity) {
        InventoryBalanceEntity balance = InventoryBalanceEntity.create(tenantId, outletId, item);
        balance.apply(new BigDecimal(quantity));
        return balance;
    }

    private InventoryBalanceEntity balance(InventoryItemEntity item, String quantity, String average) {
        InventoryBalanceEntity balance = InventoryBalanceEntity.create(tenantId, outletId, item);
        balance.receive(new BigDecimal(quantity), new BigDecimal(average)); // empty stock -> average becomes the price
        return balance;
    }

    private FnbStockMovementRequest movement(InventoryMovementEntity.Type type, String quantity, String unitCost,
                                             String reason, String key) {
        FnbStockMovementRequest request = new FnbStockMovementRequest();
        request.setType(type);
        request.setQuantity(new BigDecimal(quantity));
        request.setUnitCost(unitCost == null ? null : new BigDecimal(unitCost));
        request.setReason(reason);
        request.setIdempotencyKey(key);
        return request;
    }

    private FnbStockCountLineRequest line(UUID id, String counted) {
        FnbStockCountLineRequest line = new FnbStockCountLineRequest();
        line.setInventoryItemId(id);
        line.setCountedQuantity(new BigDecimal(counted));
        return line;
    }

    private FnbStockCountRequest count(String key, String note, FnbStockCountLineRequest... lines) {
        FnbStockCountRequest request = new FnbStockCountRequest();
        request.setIdempotencyKey(key);
        request.setNote(note);
        request.setLines(List.of(lines));
        return request;
    }

    private InventoryMovementEntity existingMovement(InventoryItemEntity item, String delta, String reason, String key,
                                                     String fingerprint, String unitCost) {
        InventoryMovementEntity movement = InventoryMovementEntity.record(tenantId, outletId, item, null,
                InventoryMovementEntity.Type.RESTOCK, new BigDecimal(delta), reason, key, fingerprint,
                ownerMembershipId, null, new BigDecimal(unitCost));
        ReflectionTestUtils.setField(movement, "id", UUID.randomUUID());
        return movement;
    }

    private InventoryMovementEntity countMovement(InventoryItemEntity item, String delta, String key, String fingerprint) {
        InventoryMovementEntity movement = InventoryMovementEntity.record(tenantId, outletId, item, null,
                InventoryMovementEntity.Type.ADJUSTMENT, new BigDecimal(delta), "Stok opname",
                "count:fp(" + key + "|" + item.getId() + ")", fingerprint, ownerMembershipId);
        ReflectionTestUtils.setField(movement, "id", UUID.randomUUID());
        return movement;
    }

    /** Deterministic stand-in for SHA-256 that exposes its input. */
    private void stubDigest() {
        when(tokens.digest(anyString())).thenAnswer(invocation -> "fp(" + invocation.getArgument(0) + ")");
    }

    private void stubMovementPath(TenantPrincipal principal, MerchantAccessContext context,
                                  InventoryItemEntity item, InventoryBalanceEntity balance) {
        when(guard.requireOutlet(principal, outletId)).thenReturn(context);
        when(inventory.item(tenantId, outletId, itemId)).thenReturn(Optional.of(item));
        when(inventory.balance(tenantId, outletId, itemId)).thenReturn(Optional.ofNullable(balance));
        stubDigest();
    }

    private void stubCountItem(InventoryItemEntity item, InventoryBalanceEntity balance) {
        when(inventory.item(tenantId, outletId, item.getId())).thenReturn(Optional.of(item));
        when(inventory.balance(tenantId, outletId, item.getId())).thenReturn(Optional.ofNullable(balance));
    }

    private void stubMovementIds() {
        doAnswer(invocation -> {
            InventoryMovementEntity saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            return null;
        }).when(inventory).persist(any(InventoryMovementEntity.class));
    }

    private InventoryMovementEntity persistedMovement() {
        ArgumentCaptor<InventoryMovementEntity> captor = ArgumentCaptor.forClass(InventoryMovementEntity.class);
        verify(inventory).persist(captor.capture());
        return captor.getValue();
    }

    private static <T> T withId(T entity, UUID id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }
}
