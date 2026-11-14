package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.entity.fnb.OrderItemEntity;
import org.lite.project.cashiersaas.entity.fnb.OrderItemOptionEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductOptionEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryBalanceEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryItemEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryMovementEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.RecipeLineEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.repository.FnbInventoryRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.service.FnbStockService;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class FnbStockServiceTest {
    @Mock private FnbOrderRepository orders;
    @Mock private FnbInventoryRepository inventory;
    @Mock private DigestService tokens;
    @InjectMocks private FnbStockService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID outletId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID membershipId = UUID.randomUUID();
    // Ascending UUIDs so the expected TreeMap (lock) order is obvious.
    private final UUID ingredientA = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID ingredientB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final UUID ingredientC = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private final UUID ingredientD = UUID.fromString("00000000-0000-0000-0000-000000000004");

    private final TenantEntity tenant = withId(new TenantEntity(), tenantId);
    private final OutletEntity outlet = withId(OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null), outletId);
    private final TenantMembershipEntity membership = withId(new TenantMembershipEntity(), membershipId);
    private final MerchantAccessContext context = new MerchantAccessContext(tenant, outlet, membership);
    private final SalesOrderEntity order = newOrder();

    // 1
    @Test
    void orderWithoutAnyRecipeReturnsEmptyAndTouchesNoStock() {
        OrderItemEntity line = line(UUID.randomUUID(), product(UUID.randomUUID()), 2);
        stubOrder(List.of(line), List.of());

        List<String> warnings = service.consumeForPaidOrder(context, order);

        assertTrue(warnings.isEmpty());
        verify(inventory, never()).balances(any(UUID.class), any(UUID.class), any());
        verify(inventory, never()).movementByKey(any(UUID.class), anyString());
        verify(inventory, never()).lock(any(InventoryBalanceEntity.class));
        verify(inventory, never()).persist(any(InventoryMovementEntity.class));
        verify(inventory, never()).persist(any(InventoryBalanceEntity.class));
    }

    // 2
    @Test
    void productRecipeDeductsQuantityTimesRecipeAndPersistsOneSaleMovement() {
        ProductEntity product = product(UUID.randomUUID());
        InventoryItemEntity beans = ingredient(ingredientA, "Biji Kopi", "g", "10");
        InventoryBalanceEntity balance = balance(beans, "100");
        OrderItemEntity line = line(UUID.randomUUID(), product, 2);
        stubOrder(List.of(line), List.of());
        when(inventory.productRecipes(tenantId, outletId, List.of(product.getId())))
                .thenReturn(List.of(productRecipe(product, beans, "18.000")));
        when(inventory.balances(tenantId, outletId, List.of(ingredientA))).thenReturn(List.of(balance));
        String fingerprint = "fingerprint-sale-1";
        when(tokens.digest("sale|" + orderId + "|" + ingredientA + "|36.000")).thenReturn(fingerprint);

        List<String> warnings = service.consumeForPaidOrder(context, order);

        assertTrue(warnings.isEmpty());
        assertDecimal("64", balance.getQuantityOnHand());
        verify(inventory).lock(balance);
        ArgumentCaptor<InventoryMovementEntity> captor = ArgumentCaptor.forClass(InventoryMovementEntity.class);
        verify(inventory, times(1)).persist(captor.capture());
        InventoryMovementEntity movement = captor.getValue();
        assertEquals(orderId, movement.getOrderId());
        assertEquals(InventoryMovementEntity.Type.SALE, movement.getType());
        assertDecimal("-36", movement.getQuantityDelta());
        assertEquals("sale:" + orderId + ":" + ingredientA, movement.getIdempotencyKey());
        assertEquals(membershipId, movement.getActorMembershipId());
        assertEquals(fingerprint, movement.getRequestFingerprint());
        assertEquals(tenantId, movement.getTenantId());
        assertEquals(outletId, movement.getOutletId());
        assertSame(beans, movement.getInventoryItem());
        assertEquals("Penjualan ORD-1", movement.getReason());
    }

    // 3
    @Test
    void productAndOptionRecipesOnSameIngredientAreSummedIntoOneMovementUsingParentLineQuantity() {
        ProductEntity product = product(UUID.randomUUID());
        ProductOptionEntity extraShot = option(UUID.randomUUID());
        InventoryItemEntity beans = ingredient(ingredientA, "Biji Kopi", "g", "10");
        InventoryBalanceEntity balance = balance(beans, "1000");
        OrderItemEntity line = line(UUID.randomUUID(), product, 3);
        OrderItemOptionEntity selected = selected(line, extraShot);
        stubOrder(List.of(line), List.of(selected));
        when(inventory.productRecipes(tenantId, outletId, List.of(product.getId())))
                .thenReturn(List.of(productRecipe(product, beans, "20.000")));
        when(inventory.optionRecipes(tenantId, outletId, List.of(extraShot.getId())))
                .thenReturn(List.of(optionRecipe(extraShot, beans, "5.000")));
        when(inventory.balances(tenantId, outletId, List.of(ingredientA))).thenReturn(List.of(balance));
        when(tokens.digest(anyString())).thenReturn("fingerprint");

        service.consumeForPaidOrder(context, order);

        // 3 x 20 (product) + 3 x 5 (option, parent line quantity) = 75
        ArgumentCaptor<InventoryMovementEntity> captor = ArgumentCaptor.forClass(InventoryMovementEntity.class);
        verify(inventory, times(1)).persist(captor.capture());
        assertDecimal("-75", captor.getValue().getQuantityDelta());
        assertDecimal("925", balance.getQuantityOnHand());
        verify(inventory, times(1)).lock(balance);
    }

    // 4
    @Test
    void twoIngredientsCreateTwoMovementsAndLockBalancesInAscendingIdOrder() {
        ProductEntity product = product(UUID.randomUUID());
        InventoryItemEntity low = ingredient(ingredientA, "Susu", "ml", "10");
        InventoryItemEntity high = ingredient(ingredientB, "Gula", "ml", "10");
        InventoryBalanceEntity lowBalance = balance(low, "1000");
        InventoryBalanceEntity highBalance = balance(high, "1000");
        stubOrder(List.of(line(UUID.randomUUID(), product, 1)), List.of());
        // Recipe returned in reverse id order on purpose.
        when(inventory.productRecipes(tenantId, outletId, List.of(product.getId())))
                .thenReturn(List.of(productRecipe(product, high, "7.000"), productRecipe(product, low, "9.000")));
        when(inventory.balances(tenantId, outletId, List.of(ingredientA, ingredientB)))
                .thenReturn(List.of(highBalance, lowBalance));
        when(tokens.digest(anyString())).thenReturn("fingerprint");

        service.consumeForPaidOrder(context, order);

        InOrder inOrder = inOrder(inventory);
        inOrder.verify(inventory).lock(lowBalance);
        inOrder.verify(inventory).persist(any(InventoryMovementEntity.class));
        inOrder.verify(inventory).lock(highBalance);
        inOrder.verify(inventory).persist(any(InventoryMovementEntity.class));
        ArgumentCaptor<InventoryMovementEntity> captor = ArgumentCaptor.forClass(InventoryMovementEntity.class);
        verify(inventory, times(2)).persist(captor.capture());
        assertSame(low, captor.getAllValues().get(0).getInventoryItem());
        assertDecimal("-9", captor.getAllValues().get(0).getQuantityDelta());
        assertSame(high, captor.getAllValues().get(1).getInventoryItem());
        assertDecimal("-7", captor.getAllValues().get(1).getQuantityDelta());
        assertDecimal("991", lowBalance.getQuantityOnHand());
        assertDecimal("993", highBalance.getQuantityOnHand());
    }

    // 5
    @Test
    void linesWithNullProductOrNullOptionAreSkippedWithoutError() {
        ProductEntity product = product(UUID.randomUUID());
        InventoryItemEntity beans = ingredient(ingredientA, "Biji Kopi", "g", "10");
        InventoryBalanceEntity balance = balance(beans, "100");
        OrderItemEntity deletedProductLine = line(UUID.randomUUID(), null, 2);
        OrderItemEntity normalLine = line(UUID.randomUUID(), product, 1);
        OrderItemOptionEntity deletedOption = selected(normalLine, null);
        stubOrder(List.of(deletedProductLine, normalLine), List.of(deletedOption));
        when(inventory.productRecipes(tenantId, outletId, List.of(product.getId())))
                .thenReturn(List.of(productRecipe(product, beans, "10.000")));
        when(inventory.balances(tenantId, outletId, List.of(ingredientA))).thenReturn(List.of(balance));
        when(tokens.digest(anyString())).thenReturn("fingerprint");

        List<String> warnings = service.consumeForPaidOrder(context, order);

        assertTrue(warnings.isEmpty());
        assertDecimal("90", balance.getQuantityOnHand());
        verify(inventory).optionRecipes(tenantId, outletId, List.of());
        verify(inventory, times(1)).persist(any(InventoryMovementEntity.class));
    }

    // 6
    @Test
    void existingMovementWithSameKeySkipsThatIngredientWithoutSecondDeductionOrWarning() {
        ProductEntity product = product(UUID.randomUUID());
        InventoryItemEntity done = ingredient(ingredientA, "Susu", "ml", "100");
        InventoryItemEntity fresh = ingredient(ingredientB, "Gula", "ml", "10");
        InventoryBalanceEntity doneBalance = balance(done, "5");
        InventoryBalanceEntity freshBalance = balance(fresh, "1000");
        stubOrder(List.of(line(UUID.randomUUID(), product, 1)), List.of());
        when(inventory.productRecipes(tenantId, outletId, List.of(product.getId())))
                .thenReturn(List.of(productRecipe(product, done, "2.000"), productRecipe(product, fresh, "10.000")));
        when(inventory.balances(tenantId, outletId, List.of(ingredientA, ingredientB)))
                .thenReturn(List.of(doneBalance, freshBalance));
        InventoryMovementEntity previous = InventoryMovementEntity.record(tenantId, outletId, done, orderId,
                InventoryMovementEntity.Type.SALE, new BigDecimal("-2.000"), "Penjualan ORD-1",
                "sale:" + orderId + ":" + ingredientA, "old", membershipId);
        when(inventory.movementByKey(tenantId, "sale:" + orderId + ":" + ingredientA)).thenReturn(Optional.of(previous));
        when(inventory.movementByKey(tenantId, "sale:" + orderId + ":" + ingredientB)).thenReturn(Optional.empty());
        when(tokens.digest(anyString())).thenReturn("fingerprint");

        List<String> warnings = service.consumeForPaidOrder(context, order);

        assertTrue(warnings.isEmpty(), "skipped ingredient must not produce a (duplicate) warning");
        assertDecimal("5", doneBalance.getQuantityOnHand());
        verify(inventory, never()).lock(doneBalance);
        verify(inventory).lock(freshBalance);
        ArgumentCaptor<InventoryMovementEntity> captor = ArgumentCaptor.forClass(InventoryMovementEntity.class);
        verify(inventory, times(1)).persist(captor.capture());
        assertSame(fresh, captor.getValue().getInventoryItem());
        assertDecimal("990", freshBalance.getQuantityOnHand());
    }

    // 7
    @Test
    void missingBalanceRowIsCreatedAndPersistedNotLockedThenApplied() {
        ProductEntity product = product(UUID.randomUUID());
        InventoryItemEntity beans = ingredient(ingredientA, "Biji Kopi", "g", "10");
        stubOrder(List.of(line(UUID.randomUUID(), product, 1)), List.of());
        when(inventory.productRecipes(tenantId, outletId, List.of(product.getId())))
                .thenReturn(List.of(productRecipe(product, beans, "18.000")));
        when(inventory.balances(tenantId, outletId, List.of(ingredientA))).thenReturn(List.of());
        when(tokens.digest(anyString())).thenReturn("fingerprint");

        List<String> warnings = service.consumeForPaidOrder(context, order);

        ArgumentCaptor<InventoryBalanceEntity> captor = ArgumentCaptor.forClass(InventoryBalanceEntity.class);
        verify(inventory).persist(captor.capture());
        InventoryBalanceEntity created = captor.getValue();
        assertNotNull(created);
        assertEquals(tenantId, created.getTenantId());
        assertEquals(outletId, created.getOutletId());
        assertSame(beans, created.getInventoryItem());
        assertDecimal("-18", created.getQuantityOnHand());
        verify(inventory, never()).lock(any(InventoryBalanceEntity.class));
        verify(inventory, times(1)).persist(any(InventoryMovementEntity.class));
        assertEquals(List.of("Biji Kopi: sisa -18 g (minus)"), warnings);
    }

    // 8
    @Test
    void stockMayGoNegativeWithoutBlockingPaymentAndWarnsWithNameAmountAndMinus() {
        ProductEntity product = product(UUID.randomUUID());
        InventoryItemEntity milk = ingredient(ingredientA, "Susu", "ml", "100");
        InventoryBalanceEntity balance = balance(milk, "10");
        stubOrder(List.of(line(UUID.randomUUID(), product, 3)), List.of());
        when(inventory.productRecipes(tenantId, outletId, List.of(product.getId())))
                .thenReturn(List.of(productRecipe(product, milk, "12.000")));
        when(inventory.balances(tenantId, outletId, List.of(ingredientA))).thenReturn(List.of(balance));
        when(tokens.digest(anyString())).thenReturn("fingerprint");

        List<String> warnings = service.consumeForPaidOrder(context, order);

        assertDecimal("-26", balance.getQuantityOnHand());
        assertEquals(1, warnings.size());
        assertEquals("Susu: sisa -26 ml (minus)", warnings.get(0));
        verify(inventory, times(1)).persist(any(InventoryMovementEntity.class));
    }

    // 9a
    @Test
    void exactlyZeroRemainingWarnsHabisWithUnit() {
        ProductEntity product = product(UUID.randomUUID());
        InventoryItemEntity sugar = ingredient(ingredientA, "Gula Aren", "ml", "100");
        InventoryBalanceEntity balance = balance(sugar, "36");
        stubOrder(List.of(line(UUID.randomUUID(), product, 2)), List.of());
        when(inventory.productRecipes(tenantId, outletId, List.of(product.getId())))
                .thenReturn(List.of(productRecipe(product, sugar, "18.000")));
        when(inventory.balances(tenantId, outletId, List.of(ingredientA))).thenReturn(List.of(balance));
        when(tokens.digest(anyString())).thenReturn("fingerprint");

        List<String> warnings = service.consumeForPaidOrder(context, order);

        assertEquals(List.of("Gula Aren: sisa 0 ml (habis)"), warnings);
    }

    // 9b
    @Test
    void remainingAtOrBelowReorderLevelWarnsMenipisAndAboveWarnsNothing() {
        ProductEntity product = product(UUID.randomUUID());
        InventoryItemEntity belowLevel = ingredient(ingredientA, "Boba", "g", "100");
        InventoryItemEntity exactlyAtLevel = ingredient(ingredientB, "Matcha", "g", "100");
        InventoryItemEntity justAbove = ingredient(ingredientC, "Oat Milk", "ml", "100");
        InventoryBalanceEntity belowBalance = balance(belowLevel, "150");
        InventoryBalanceEntity atBalance = balance(exactlyAtLevel, "150");
        InventoryBalanceEntity aboveBalance = balance(justAbove, "151");
        stubOrder(List.of(line(UUID.randomUUID(), product, 1)), List.of());
        when(inventory.productRecipes(tenantId, outletId, List.of(product.getId())))
                .thenReturn(List.of(
                        productRecipe(product, belowLevel, "60.000"),       // 90  -> LOW (below level)
                        productRecipe(product, exactlyAtLevel, "50.000"),   // 100 -> LOW (equal to level)
                        productRecipe(product, justAbove, "50.000")));      // 101 -> OK
        when(inventory.balances(tenantId, outletId, List.of(ingredientA, ingredientB, ingredientC)))
                .thenReturn(List.of(belowBalance, atBalance, aboveBalance));
        when(tokens.digest(anyString())).thenReturn("fingerprint");

        List<String> warnings = service.consumeForPaidOrder(context, order);

        assertEquals(List.of("Boba: sisa 90 g (menipis)", "Matcha: sisa 100 g (menipis)"), warnings);
        assertDecimal("101", aboveBalance.getQuantityOnHand());
    }

    // 10
    @Test
    void recipesAndOrderRowsAreFetchedWithSingleBatchedCalls() {
        ProductEntity espresso = product(UUID.fromString("10000000-0000-0000-0000-000000000001"));
        ProductEntity latte = product(UUID.fromString("10000000-0000-0000-0000-000000000002"));
        ProductOptionEntity extraShot = option(UUID.fromString("20000000-0000-0000-0000-000000000001"));
        ProductOptionEntity noRecipeOption = option(UUID.fromString("20000000-0000-0000-0000-000000000002"));
        UUID firstLineId = UUID.fromString("30000000-0000-0000-0000-000000000001");
        UUID secondLineId = UUID.fromString("30000000-0000-0000-0000-000000000002");
        OrderItemEntity first = line(firstLineId, espresso, 1);
        OrderItemEntity second = line(secondLineId, latte, 2);
        List<OrderItemOptionEntity> selections = List.of(
                selected(first, extraShot), selected(first, noRecipeOption), selected(second, extraShot));
        InventoryItemEntity beans = ingredient(ingredientA, "Biji Kopi", "g", "10");
        InventoryItemEntity milk = ingredient(ingredientB, "Susu", "ml", "10");
        InventoryBalanceEntity beansBalance = balance(beans, "1000");
        InventoryBalanceEntity milkBalance = balance(milk, "1000");
        stubOrder(List.of(first, second), selections);
        when(inventory.productRecipes(tenantId, outletId, List.of(espresso.getId(), latte.getId())))
                .thenReturn(List.of(productRecipe(espresso, beans, "10.000"), productRecipe(latte, milk, "7.000")));
        when(inventory.optionRecipes(tenantId, outletId, List.of(extraShot.getId(), noRecipeOption.getId())))
                .thenReturn(List.of(optionRecipe(extraShot, beans, "5.000")));
        when(inventory.balances(tenantId, outletId, List.of(ingredientA, ingredientB)))
                .thenReturn(List.of(beansBalance, milkBalance));
        when(tokens.digest(anyString())).thenReturn("fingerprint");

        List<String> warnings = service.consumeForPaidOrder(context, order);

        assertTrue(warnings.isEmpty());
        verify(orders, times(1)).items(tenantId, outletId, List.of(orderId));
        verify(orders, times(1)).itemOptions(tenantId, outletId, List.of(firstLineId, secondLineId));
        verify(inventory, times(1)).productRecipes(tenantId, outletId, List.of(espresso.getId(), latte.getId()));
        verify(inventory, times(1)).optionRecipes(tenantId, outletId, List.of(extraShot.getId(), noRecipeOption.getId()));
        verify(inventory, times(1)).balances(tenantId, outletId, List.of(ingredientA, ingredientB));
        // beans: 1 x 10 (espresso) + 5 x 1 (extra shot on line 1) + 5 x 2 (extra shot on line 2) = 25; milk: 2 x 7 = 14
        assertDecimal("975", beansBalance.getQuantityOnHand());
        assertDecimal("986", milkBalance.getQuantityOnHand());
        verify(inventory, times(2)).persist(any(InventoryMovementEntity.class));
    }

    // 11
    @Test
    void reversalPutsBackNegatedSaleDeltasLinksTheOriginalAndLocksInItemOrder() {
        InventoryItemEntity low = ingredient(ingredientA, "Susu", "ml", "10");
        InventoryItemEntity high = ingredient(ingredientB, "Gula", "ml", "10");
        InventoryBalanceEntity lowBalance = balance(low, "991");
        InventoryBalanceEntity highBalance = balance(high, "993");
        InventoryMovementEntity lowSale = sale(low, "-9.000");
        InventoryMovementEntity highSale = sale(high, "-7.000");
        ReflectionTestUtils.setField(lowSale, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(highSale, "id", UUID.randomUUID());
        when(inventory.saleMovements(tenantId, outletId, orderId)).thenReturn(List.of(lowSale, highSale));
        when(inventory.balances(tenantId, outletId, List.of(ingredientA, ingredientB)))
                .thenReturn(List.of(highBalance, lowBalance));
        when(tokens.digest(anyString())).thenReturn("fingerprint");

        List<String> warnings = service.reverseForRefundedOrder(context, order);

        assertTrue(warnings.isEmpty());
        assertDecimal("1000", lowBalance.getQuantityOnHand());
        assertDecimal("1000", highBalance.getQuantityOnHand());
        InOrder inOrder = inOrder(inventory);
        inOrder.verify(inventory).lock(lowBalance);
        inOrder.verify(inventory).persist(any(InventoryMovementEntity.class));
        inOrder.verify(inventory).lock(highBalance);
        inOrder.verify(inventory).persist(any(InventoryMovementEntity.class));
        ArgumentCaptor<InventoryMovementEntity> captor = ArgumentCaptor.forClass(InventoryMovementEntity.class);
        verify(inventory, times(2)).persist(captor.capture());
        InventoryMovementEntity first = captor.getAllValues().get(0);
        assertEquals(InventoryMovementEntity.Type.SALE_REVERSAL, first.getType());
        assertDecimal("9", first.getQuantityDelta());
        assertEquals(lowSale.getId(), first.getReversalOfMovementId());
        assertEquals("sale-reversal:" + orderId + ":" + ingredientA, first.getIdempotencyKey());
        assertEquals("Refund pesanan ORD-1", first.getReason());
        assertEquals(orderId, first.getOrderId());
        assertEquals(membershipId, first.getActorMembershipId());
        assertSame(low, first.getInventoryItem());
        InventoryMovementEntity second = captor.getAllValues().get(1);
        assertDecimal("7", second.getQuantityDelta());
        assertEquals(highSale.getId(), second.getReversalOfMovementId());
        assertEquals("sale-reversal:" + orderId + ":" + ingredientB, second.getIdempotencyKey());
    }

    // 12
    @Test
    void reversalSkipsAnItemWhoseReversalKeyExistsAndOrdersWithoutSalesAreNoOps() {
        when(inventory.saleMovements(tenantId, outletId, orderId)).thenReturn(List.of());
        assertTrue(service.reverseForRefundedOrder(context, order).isEmpty());
        verify(inventory, never()).balances(any(UUID.class), any(UUID.class), any());

        InventoryItemEntity done = ingredient(ingredientA, "Susu", "ml", "100");
        InventoryItemEntity fresh = ingredient(ingredientB, "Gula", "ml", "10");
        InventoryBalanceEntity doneBalance = balance(done, "5");
        InventoryBalanceEntity freshBalance = balance(fresh, "1000");
        when(inventory.saleMovements(tenantId, outletId, orderId))
                .thenReturn(List.of(sale(done, "-2.000"), sale(fresh, "-10.000")));
        when(inventory.balances(tenantId, outletId, List.of(ingredientA, ingredientB)))
                .thenReturn(List.of(doneBalance, freshBalance));
        String doneKey = "sale-reversal:" + orderId + ":" + ingredientA;
        when(inventory.movementByKey(tenantId, doneKey)).thenReturn(Optional.of(sale(done, "2.000")));
        when(inventory.movementByKey(tenantId, "sale-reversal:" + orderId + ":" + ingredientB)).thenReturn(Optional.empty());
        when(tokens.digest(anyString())).thenReturn("fingerprint");

        List<String> warnings = service.reverseForRefundedOrder(context, order);

        assertTrue(warnings.isEmpty(), "skipped item gives no (duplicate) warning");
        assertDecimal("5", doneBalance.getQuantityOnHand());
        verify(inventory, never()).lock(doneBalance);
        assertDecimal("1010", freshBalance.getQuantityOnHand());
        verify(inventory, times(1)).persist(any(InventoryMovementEntity.class));
    }

    // ---------------------------------------------------------------- M6: unit cost / COGS

    // 13
    @Test
    void inactiveIngredientIsNotDeductedAndAddsNoCostWhileActiveOneIs() {
        ProductEntity product = product(UUID.randomUUID());
        InventoryItemEntity beans = ingredient(ingredientA, "Biji Kopi", "g", "10");
        InventoryItemEntity retired = ingredient(ingredientB, "Sirup Lama", "ml", "10");
        retired.deactivate();
        InventoryBalanceEntity beansBalance = balance(beans, "100", "2");
        InventoryBalanceEntity retiredBalance = balance(retired, "100", "50");
        OrderItemEntity line = line(UUID.randomUUID(), product, 2);
        stubOrder(List.of(line), List.of());
        when(inventory.productRecipes(tenantId, outletId, List.of(product.getId())))
                .thenReturn(List.of(productRecipe(product, beans, "10.000"), productRecipe(product, retired, "5.000")));
        when(inventory.balances(tenantId, outletId, List.of(ingredientA))).thenReturn(List.of(beansBalance));
        when(tokens.digest(anyString())).thenReturn("fingerprint");

        service.consumeForPaidOrder(context, order);

        assertDecimal("80", beansBalance.getQuantityOnHand());
        assertDecimal("100", retiredBalance.getQuantityOnHand());
        verify(inventory, never()).lock(retiredBalance);
        ArgumentCaptor<InventoryMovementEntity> captor = ArgumentCaptor.forClass(InventoryMovementEntity.class);
        verify(inventory, times(1)).persist(captor.capture());
        assertSame(beans, captor.getValue().getInventoryItem());
        // 2 x 10 x 2 = 40; the inactive ingredient (average 50) is not part of the cost
        assertDecimal("40", line.getCostAmount());
    }

    // 14
    @Test
    void recipeWithOnlyInactiveIngredientsTouchesNoStockAndLeavesCostNull() {
        ProductEntity product = product(UUID.randomUUID());
        InventoryItemEntity retired = ingredient(ingredientA, "Sirup Lama", "ml", "10");
        retired.deactivate();
        OrderItemEntity line = line(UUID.randomUUID(), product, 1);
        stubOrder(List.of(line), List.of());
        when(inventory.productRecipes(tenantId, outletId, List.of(product.getId())))
                .thenReturn(List.of(productRecipe(product, retired, "5.000")));

        List<String> warnings = service.consumeForPaidOrder(context, order);

        assertTrue(warnings.isEmpty());
        verify(inventory, never()).balances(any(UUID.class), any(UUID.class), any());
        verify(inventory, never()).persist(any(InventoryMovementEntity.class));
        assertNull(line.getCostAmount());
    }

    // 15
    @Test
    void saleMovementSnapshotsTheBalanceAverageAndNeverChangesIt() {
        ProductEntity product = product(UUID.randomUUID());
        InventoryItemEntity beans = ingredient(ingredientA, "Biji Kopi", "g", "10");
        InventoryBalanceEntity balance = balance(beans, "100", "0.0123");
        OrderItemEntity line = line(UUID.randomUUID(), product, 2);
        stubOrder(List.of(line), List.of());
        when(inventory.productRecipes(tenantId, outletId, List.of(product.getId())))
                .thenReturn(List.of(productRecipe(product, beans, "18.000")));
        when(inventory.balances(tenantId, outletId, List.of(ingredientA))).thenReturn(List.of(balance));
        when(tokens.digest(anyString())).thenReturn("fingerprint");

        service.consumeForPaidOrder(context, order);

        ArgumentCaptor<InventoryMovementEntity> captor = ArgumentCaptor.forClass(InventoryMovementEntity.class);
        verify(inventory).persist(captor.capture());
        assertDecimal("0.0123", captor.getValue().getUnitCost());
        assertEquals(new BigDecimal("0.0123"), balance.getAverageUnitCost());
        // 36 x 0.0123 = 0.4428 -> scale 2 HALF_UP
        assertEquals(new BigDecimal("0.44"), line.getCostAmount());
    }

    // 16
    @Test
    void costOfAnItemSumsProductAndItsOwnOptionRecipesOnly() {
        ProductEntity espresso = product(UUID.fromString("10000000-0000-0000-0000-000000000001"));
        ProductEntity latte = product(UUID.fromString("10000000-0000-0000-0000-000000000002"));
        ProductOptionEntity extraShot = option(UUID.fromString("20000000-0000-0000-0000-000000000001"));
        InventoryItemEntity beans = ingredient(ingredientA, "Biji Kopi", "g", "10");
        InventoryItemEntity milk = ingredient(ingredientB, "Susu", "ml", "10");
        InventoryBalanceEntity beansBalance = balance(beans, "1000", "2");
        InventoryBalanceEntity milkBalance = balance(milk, "1000", "0.5");
        OrderItemEntity first = line(UUID.fromString("30000000-0000-0000-0000-000000000001"), espresso, 3);
        OrderItemEntity second = line(UUID.fromString("30000000-0000-0000-0000-000000000002"), latte, 2);
        stubOrder(List.of(first, second), List.of(selected(first, extraShot)));
        when(inventory.productRecipes(tenantId, outletId, List.of(espresso.getId(), latte.getId())))
                .thenReturn(List.of(productRecipe(espresso, beans, "20.000"), productRecipe(latte, milk, "100.000")));
        when(inventory.optionRecipes(tenantId, outletId, List.of(extraShot.getId())))
                .thenReturn(List.of(optionRecipe(extraShot, beans, "5.000")));
        when(inventory.balances(tenantId, outletId, List.of(ingredientA, ingredientB)))
                .thenReturn(List.of(beansBalance, milkBalance));
        when(tokens.digest(anyString())).thenReturn("fingerprint");

        service.consumeForPaidOrder(context, order);

        // first: 3 x (20 + 5) x 2 = 150; second: 2 x 100 x 0.5 = 100 (the option belongs to the first line only)
        assertDecimal("150", first.getCostAmount());
        assertDecimal("100", second.getCostAmount());
    }

    // 17
    @Test
    void costIsNullWhenAnyUsedIngredientHasNoAverageOrNoBalanceRow() {
        ProductEntity noAverageProduct = product(UUID.fromString("10000000-0000-0000-0000-000000000001"));
        ProductEntity mixedProduct = product(UUID.fromString("10000000-0000-0000-0000-000000000002"));
        ProductEntity noBalanceProduct = product(UUID.fromString("10000000-0000-0000-0000-000000000003"));
        InventoryItemEntity beans = ingredient(ingredientA, "Biji Kopi", "g", "10");      // balance, no average
        InventoryItemEntity milk = ingredient(ingredientB, "Susu", "ml", "10");           // balance + average
        InventoryItemEntity ghost = ingredient(ingredientC, "Es Batu", "g", "10");        // no balance row
        InventoryBalanceEntity beansBalance = balance(beans, "1000");
        InventoryBalanceEntity milkBalance = balance(milk, "1000", "0.5");
        OrderItemEntity noAverage = line(UUID.fromString("30000000-0000-0000-0000-000000000001"), noAverageProduct, 1);
        OrderItemEntity mixed = line(UUID.fromString("30000000-0000-0000-0000-000000000002"), mixedProduct, 1);
        OrderItemEntity noBalance = line(UUID.fromString("30000000-0000-0000-0000-000000000003"), noBalanceProduct, 1);
        stubOrder(List.of(noAverage, mixed, noBalance), List.of());
        when(inventory.productRecipes(tenantId, outletId,
                List.of(noAverageProduct.getId(), mixedProduct.getId(), noBalanceProduct.getId())))
                .thenReturn(List.of(productRecipe(noAverageProduct, beans, "10.000"),
                        productRecipe(mixedProduct, beans, "10.000"), productRecipe(mixedProduct, milk, "10.000"),
                        productRecipe(noBalanceProduct, ghost, "10.000")));
        when(inventory.balances(tenantId, outletId, List.of(ingredientA, ingredientB, ingredientC)))
                .thenReturn(List.of(beansBalance, milkBalance));
        when(tokens.digest(anyString())).thenReturn("fingerprint");

        service.consumeForPaidOrder(context, order);

        assertNull(noAverage.getCostAmount());
        assertNull(mixed.getCostAmount(), "one used ingredient without a cost makes the whole item cost unknown");
        assertNull(noBalance.getCostAmount());
        ArgumentCaptor<InventoryMovementEntity> captor = ArgumentCaptor.forClass(InventoryMovementEntity.class);
        // 3 SALE movements + 1 balance created on the fly for the ingredient without a balance row
        verify(inventory, times(3)).persist(captor.capture());
        // beans (no average), milk (0.5), ghost (new balance -> no average)
        assertNull(captor.getAllValues().get(0).getUnitCost());
        assertDecimal("0.5", captor.getAllValues().get(1).getUnitCost());
        assertNull(captor.getAllValues().get(2).getUnitCost());
    }

    // 18
    @Test
    void itemWithoutAnyRecipeOrWithoutProductGetsNullCostWhileCostedItemsKeepTheirs() {
        ProductEntity withRecipe = product(UUID.fromString("10000000-0000-0000-0000-000000000001"));
        ProductEntity withoutRecipe = product(UUID.fromString("10000000-0000-0000-0000-000000000002"));
        InventoryItemEntity beans = ingredient(ingredientA, "Biji Kopi", "g", "10");
        InventoryBalanceEntity balance = balance(beans, "1000", "2");
        OrderItemEntity costed = line(UUID.fromString("30000000-0000-0000-0000-000000000001"), withRecipe, 1);
        OrderItemEntity bare = line(UUID.fromString("30000000-0000-0000-0000-000000000002"), withoutRecipe, 1);
        OrderItemEntity deleted = line(UUID.fromString("30000000-0000-0000-0000-000000000003"), null, 1);
        stubOrder(List.of(costed, bare, deleted), List.of());
        when(inventory.productRecipes(tenantId, outletId, List.of(withRecipe.getId(), withoutRecipe.getId())))
                .thenReturn(List.of(productRecipe(withRecipe, beans, "10.000")));
        when(inventory.balances(tenantId, outletId, List.of(ingredientA))).thenReturn(List.of(balance));
        when(tokens.digest(anyString())).thenReturn("fingerprint");

        service.consumeForPaidOrder(context, order);

        assertDecimal("20", costed.getCostAmount());
        assertNull(bare.getCostAmount());
        assertNull(deleted.getCostAmount());
    }

    // 19
    @Test
    void alreadyRecordedCostIsNeverOverwrittenOnAReplayedPayment() {
        ProductEntity product = product(UUID.randomUUID());
        InventoryItemEntity beans = ingredient(ingredientA, "Biji Kopi", "g", "10");
        InventoryBalanceEntity balance = balance(beans, "100", "9");
        OrderItemEntity line = line(UUID.randomUUID(), product, 1);
        line.recordCost(new BigDecimal("12.345"));
        stubOrder(List.of(line), List.of());
        when(inventory.productRecipes(tenantId, outletId, List.of(product.getId())))
                .thenReturn(List.of(productRecipe(product, beans, "10.000")));
        when(inventory.balances(tenantId, outletId, List.of(ingredientA))).thenReturn(List.of(balance));
        when(inventory.movementByKey(tenantId, "sale:" + orderId + ":" + ingredientA))
                .thenReturn(Optional.of(sale(beans, "-10.000")));

        service.consumeForPaidOrder(context, order);

        assertEquals(new BigDecimal("12.35"), line.getCostAmount());
        assertDecimal("100", balance.getQuantityOnHand());
        verify(inventory, never()).persist(any(InventoryMovementEntity.class));
    }

    // 20
    @Test
    void reversalCopiesTheSaleUnitCostAndLeavesTheAverageAlone() {
        InventoryItemEntity beans = ingredient(ingredientA, "Biji Kopi", "g", "10");
        InventoryItemEntity milk = ingredient(ingredientB, "Susu", "ml", "10");
        InventoryBalanceEntity beansBalance = balance(beans, "90", "2");
        InventoryBalanceEntity milkBalance = balance(milk, "90", "0.5");
        InventoryMovementEntity beansSale = sale(beans, "-10.000", "1.7500"); // sold at an older average
        InventoryMovementEntity milkSale = sale(milk, "-10.000", null);       // sold before any cost was known
        ReflectionTestUtils.setField(beansSale, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(milkSale, "id", UUID.randomUUID());
        when(inventory.saleMovements(tenantId, outletId, orderId)).thenReturn(List.of(beansSale, milkSale));
        when(inventory.balances(tenantId, outletId, List.of(ingredientA, ingredientB)))
                .thenReturn(List.of(beansBalance, milkBalance));
        when(tokens.digest(anyString())).thenReturn("fingerprint");

        service.reverseForRefundedOrder(context, order);

        ArgumentCaptor<InventoryMovementEntity> captor = ArgumentCaptor.forClass(InventoryMovementEntity.class);
        verify(inventory, times(2)).persist(captor.capture());
        assertEquals(InventoryMovementEntity.Type.SALE_REVERSAL, captor.getAllValues().get(0).getType());
        assertDecimal("1.75", captor.getAllValues().get(0).getUnitCost());
        assertNull(captor.getAllValues().get(1).getUnitCost());
        assertEquals(new BigDecimal("2.0000"), beansBalance.getAverageUnitCost());
        assertEquals(new BigDecimal("0.5000"), milkBalance.getAverageUnitCost());
        assertDecimal("100", beansBalance.getQuantityOnHand());
    }

    private InventoryMovementEntity sale(InventoryItemEntity item, String delta, String unitCost) {
        return InventoryMovementEntity.record(tenantId, outletId, item, orderId, InventoryMovementEntity.Type.SALE,
                new BigDecimal(delta), "Penjualan ORD-1", "sale:" + orderId + ":" + item.getId(), "old", membershipId,
                null, unitCost == null ? null : new BigDecimal(unitCost));
    }

    private InventoryBalanceEntity balance(InventoryItemEntity item, String onHand, String average) {
        InventoryBalanceEntity balance = InventoryBalanceEntity.create(tenantId, outletId, item);
        balance.receive(new BigDecimal(onHand), new BigDecimal(average)); // empty stock -> average becomes the price
        return balance;
    }

    private InventoryMovementEntity sale(InventoryItemEntity item, String delta) {
        return InventoryMovementEntity.record(tenantId, outletId, item, orderId, InventoryMovementEntity.Type.SALE,
                new BigDecimal(delta), "Penjualan ORD-1", "sale:" + orderId + ":" + item.getId(), "old", membershipId);
    }

    private void stubOrder(List<OrderItemEntity> items, List<OrderItemOptionEntity> options) {
        when(orders.items(tenantId, outletId, List.of(orderId))).thenReturn(items);
        when(orders.itemOptions(tenantId, outletId, items.stream().map(OrderItemEntity::getId).toList())).thenReturn(options);
    }

    private SalesOrderEntity newOrder() {
        SalesOrderEntity value = withId(new SalesOrderEntity(), orderId);
        ReflectionTestUtils.setField(value, "orderNumber", "ORD-1");
        return value;
    }

    private InventoryItemEntity ingredient(UUID id, String name, String unit, String reorderLevel) {
        return withId(InventoryItemEntity.create(tenant, outlet, null, name, unit, new BigDecimal(reorderLevel)), id);
    }

    private InventoryBalanceEntity balance(InventoryItemEntity item, String onHand) {
        InventoryBalanceEntity balance = InventoryBalanceEntity.create(tenantId, outletId, item);
        balance.apply(new BigDecimal(onHand));
        return balance;
    }

    private ProductEntity product(UUID id) {
        return withId(new ProductEntity(), id);
    }

    private ProductOptionEntity option(UUID id) {
        return withId(new ProductOptionEntity(), id);
    }

    private OrderItemEntity line(UUID id, ProductEntity product, int quantity) {
        OrderItemEntity line = withId(new OrderItemEntity(), id);
        ReflectionTestUtils.setField(line, "product", product);
        ReflectionTestUtils.setField(line, "quantity", quantity);
        return line;
    }

    private OrderItemOptionEntity selected(OrderItemEntity parent, ProductOptionEntity option) {
        OrderItemOptionEntity selected = withId(new OrderItemOptionEntity(), UUID.randomUUID());
        ReflectionTestUtils.setField(selected, "orderItem", parent);
        ReflectionTestUtils.setField(selected, "productOption", option);
        return selected;
    }

    private RecipeLineEntity productRecipe(ProductEntity product, InventoryItemEntity item, String quantityPerUnit) {
        return RecipeLineEntity.forProduct(tenant, outlet, product, item, new BigDecimal(quantityPerUnit));
    }

    private RecipeLineEntity optionRecipe(ProductOptionEntity option, InventoryItemEntity item, String quantityPerUnit) {
        return RecipeLineEntity.forOption(tenant, outlet, option, item, new BigDecimal(quantityPerUnit));
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
