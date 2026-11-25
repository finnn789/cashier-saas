package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.response.FnbMenuMarginReportResponse;
import org.lite.project.cashiersaas.dto.response.FnbMenuMarginResponse;
import org.lite.project.cashiersaas.entity.shared.catalog.CategoryEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryBalanceEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryItemEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.RecipeLineEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.exception.runtime.PlanUpgradeRequiredException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.FnbCatalogRepository;
import org.lite.project.cashiersaas.repository.FnbInventoryRepository;
import org.lite.project.cashiersaas.repository.OutletRepository;
import org.lite.project.cashiersaas.service.FnbMenuMarginService;
import org.lite.project.cashiersaas.service.MerchantAccessGuard;
import org.lite.project.cashiersaas.service.PlanEntitlementService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** TASK-085: HPP, margin, status, suggested price and restock warnings per menu. */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class FnbMenuMarginServiceTest {
    @Mock private MerchantAccessGuard guard;
    @Mock private PlanEntitlementService planEntitlementService;
    @Mock private FnbCatalogRepository catalog;
    @Mock private FnbInventoryRepository inventory;
    @Mock private OutletRepository outlets;
    @InjectMocks private FnbMenuMarginService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID outletId = UUID.randomUUID();
    private final TenantPrincipal owner = new TenantPrincipal(UUID.randomUUID(), tenantId, outletId,
            UUID.randomUUID(), UUID.randomUUID(), Set.of(Role.OWNERMERCHANT));
    private final TenantEntity tenant = new TenantEntity(); // target margin defaults to 60.00
    private final OutletEntity outlet = outlet();
    private final MerchantAccessContext ownerContext = new MerchantAccessContext(tenant, outlet, TenantMembershipEntity.grantOwner(null, tenant));
    private final CategoryEntity category = CategoryEntity.create(tenant, outlet, "Minuman", 1);
    private final InventoryItemEntity susu = item("Susu");          // average cost 50
    private final InventoryItemEntity kopi = item("Kopi");          // average cost 100
    private final InventoryItemEntity bubuk = item("Bubuk");        // balance without an average cost
    private final InventoryItemEntity cokelat = item("Cokelat");    // no balance at all
    private final InventoryItemEntity lama = item("Lama");          // deactivated

    @Test
    void menusGetTheirStatusHppAndMarginAndAreOrderedBelowTargetByMarginThenUnknownCostNoRecipeAndOk() {
        stubMenus();
        when(guard.requireOwner(owner, outletId)).thenReturn(ownerContext);

        FnbMenuMarginReportResponse report = service.menuMargins(owner, outletId, false);

        assertEquals(outletId, report.getOutletId());
        assertEquals("Outlet", report.getOutletName());
        assertEquals(new BigDecimal("60.00"), report.getTargetMarginPct());
        assertEquals(2, report.getBelowTargetCount());
        assertEquals(List.of("Moka", "Latte", "Cokelat Panas", "Es Kopi", "Roti", "Gratis", "Teh"),
                report.getMenus().stream().map(FnbMenuMarginResponse::getName).toList());
        FnbMenuMarginResponse moka = report.getMenus().get(0);
        assertEquals("BELOW_TARGET", moka.getStatus());
        assertEquals("Minuman", moka.getCategoryName());
        assertEquals(new BigDecimal("20000"), moka.getPrice());
        assertEquals(new BigDecimal("11000.00"), moka.getHpp());    // 120 * 50 + 50 * 100
        assertEquals(new BigDecimal("45.00"), moka.getMarginPct());
        assertEquals(new BigDecimal("27500.00"), moka.getSuggestedPrice());
        FnbMenuMarginResponse latte = report.getMenus().get(1);
        assertEquals("BELOW_TARGET", latte.getStatus());
        assertEquals(new BigDecimal("50.00"), latte.getMarginPct());
        assertEquals(new BigDecimal("25000.00"), latte.getSuggestedPrice());
        FnbMenuMarginResponse unknownCost = report.getMenus().get(2);
        assertEquals("COST_UNKNOWN", unknownCost.getStatus());
        assertEquals(List.of("Bubuk", "Cokelat"), unknownCost.getMissingCostItems()); // sorted and distinct
        assertNull(unknownCost.getHpp());
        assertNull(unknownCost.getMarginPct());
        assertEquals("NO_RECIPE", report.getMenus().get(3).getStatus()); // only a deactivated ingredient
        assertEquals("NO_RECIPE", report.getMenus().get(4).getStatus());
        FnbMenuMarginResponse free = report.getMenus().get(5);
        assertEquals("OK", free.getStatus());
        assertEquals(new BigDecimal("500.00"), free.getHpp());
        assertNull(free.getMarginPct()); // margin is undefined for a price of 0
        FnbMenuMarginResponse teh = report.getMenus().get(6);
        assertEquals("OK", teh.getStatus());
        assertEquals(new BigDecimal("90.00"), teh.getMarginPct());
        assertNull(teh.getSuggestedPrice());
    }

    @Test
    void belowTargetOnlyKeepsJustTheBelowTargetMenusButStillCountsThemAll() {
        stubMenus();
        when(guard.requireOwner(owner, outletId)).thenReturn(ownerContext);

        FnbMenuMarginReportResponse report = service.menuMargins(owner, outletId, true);

        assertEquals(List.of("Moka", "Latte"), report.getMenus().stream().map(FnbMenuMarginResponse::getName).toList());
        assertEquals(2, report.getBelowTargetCount());
    }

    @ParameterizedTest
    @CsvSource({
            // price, average cost of the only ingredient (1 per menu), target, suggested price
            "10000, 10000, 60, 25000.00",   // an exact multiple of 500 stays
            "10000, 10001, 60, 25500.00",   // 25002.5 rounds up to the next 500
            "3000, 3000, 50, 6000.00",
            "3000, 3001, 50, 6500.00",
            "500, 1000, 0, 1000.00",        // target 0: price must at least cover the cost
            "5, 7, 33.33, 500.00"           // tiny cost still rounds up to one Rp500 step
    })
    void suggestedPriceRoundsUpToTheNextRp500(String price, String cost, String target, String suggested) {
        tenant.changeTargetMargin(new BigDecimal(target));
        ProductEntity menu = product("Menu", price);
        when(guard.requireOwner(owner, outletId)).thenReturn(ownerContext);
        when(catalog.products(tenantId, outletId)).thenReturn(List.of(menu));
        when(inventory.productRecipes(eq(tenantId), eq(outletId), anyList())).thenReturn(List.of(recipe(menu, susu, "1")));
        when(inventory.balances(eq(tenantId), eq(outletId), anyList())).thenReturn(List.of(priced(susu, cost)));

        FnbMenuMarginResponse result = service.menuMargins(owner, outletId, false).getMenus().get(0);

        assertEquals("BELOW_TARGET", result.getStatus());
        assertEquals(new BigDecimal(suggested), result.getSuggestedPrice());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void menuMarginsNeedTheOwnerAndThePlanBeforeReadingAnything(boolean notOwner) {
        if (notOwner) when(guard.requireOwner(owner, outletId)).thenThrow(new ResourceNotFoundException());
        else doThrow(new PlanUpgradeRequiredException(PlanCode.PRO)).when(planEntitlementService).require(tenantId, PlanCode.PRO);

        assertThrows(RuntimeException.class,
                () -> service.menuMargins(owner, outletId, false));

        verifyNoInteractions(catalog, inventory);
    }

    @Test
    void outletMarginsOfAnOutletOutsideTheTenantIsNotFound() {
        UUID foreign = UUID.randomUUID();
        when(outlets.findInTenant(tenantId, foreign)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.outletMargins(tenantId, foreign));

        verifyNoInteractions(catalog, inventory);
    }

    @Test
    void restockWarningsListOnlyBelowTargetMenusUsingTheItemInIndonesianWorstMarginFirst() {
        stubMenus();

        List<String> warnings = service.restockWarnings(tenantId, outletId, susu.getId(), new BigDecimal("60.00"));

        // Teh and Gratis also use Susu but are OK; the id-ID format uses "," for decimals and "." for thousands
        assertEquals(List.of(
                "Margin Moka turun ke 45,00% (target 60,00%), saran harga Rp27.500",
                "Margin Latte turun ke 50,00% (target 60,00%), saran harga Rp25.000"), warnings);
        // Cokelat Panas uses Bubuk but its cost is unknown, so it is not below target
        assertEquals(List.of(), service.restockWarnings(tenantId, outletId, bubuk.getId(), new BigDecimal("60.00")));
    }

    // ---------------------------------------------------------------- fixtures

    /** Latte 50% and Moka 45% (below 60), Cokelat Panas unknown cost, Es Kopi and Roti no recipe, Gratis price 0, Teh 90%. */
    private void stubMenus() {
        lama.deactivate();
        ProductEntity latte = product("Latte", "20000");
        ProductEntity moka = product("Moka", "20000");
        ProductEntity cokelatPanas = product("Cokelat Panas", "15000");
        ProductEntity esKopi = product("Es Kopi", "18000");
        ProductEntity roti = product("Roti", "12000");
        ProductEntity gratis = product("Gratis", "0");
        ProductEntity teh = product("Teh", "10000");
        when(catalog.products(tenantId, outletId)).thenReturn(List.of(latte, moka, cokelatPanas, esKopi, roti, gratis, teh));
        when(inventory.productRecipes(eq(tenantId), eq(outletId), anyList())).thenReturn(List.of(
                recipe(latte, susu, "100"), recipe(latte, kopi, "50"),
                recipe(moka, susu, "120"), recipe(moka, kopi, "50"),
                recipe(teh, susu, "20"), recipe(gratis, susu, "10"),
                recipe(cokelatPanas, cokelat, "5"), recipe(cokelatPanas, bubuk, "5"), recipe(cokelatPanas, cokelat, "3"),
                recipe(esKopi, lama, "1")));
        InventoryBalanceEntity bubukBalance = InventoryBalanceEntity.create(tenantId, outletId, bubuk);
        bubukBalance.apply(new BigDecimal("10")); // stock without any purchase price
        when(inventory.balances(eq(tenantId), eq(outletId), anyList())).thenReturn(List.of(
                priced(susu, "50"), priced(kopi, "100"), bubukBalance, priced(lama, "10")));
    }

    private OutletEntity outlet() {
        OutletEntity value = OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null);
        ReflectionTestUtils.setField(value, "id", outletId);
        return value;
    }

    private InventoryItemEntity item(String name) {
        InventoryItemEntity item = InventoryItemEntity.create(tenant, outlet, null, name, "ml", BigDecimal.ONE);
        ReflectionTestUtils.setField(item, "id", UUID.randomUUID());
        return item;
    }

    private ProductEntity product(String name, String price) {
        ProductEntity product = ProductEntity.create(tenant, outlet, new ProductEntity.Details(category, name, null, new BigDecimal(price), null, true, 0));
        ReflectionTestUtils.setField(product, "id", UUID.randomUUID());
        return product;
    }

    private RecipeLineEntity recipe(ProductEntity product, InventoryItemEntity item, String quantity) {
        return RecipeLineEntity.forProduct(tenant, outlet, product, item, new BigDecimal(quantity));
    }

    private InventoryBalanceEntity priced(InventoryItemEntity item, String averageCost) {
        InventoryBalanceEntity balance = InventoryBalanceEntity.create(tenantId, outletId, item);
        balance.receive(new BigDecimal("100"), new BigDecimal(averageCost));
        return balance;
    }
}
