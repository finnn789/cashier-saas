package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.FnbCatalogCopyRequest;
import org.lite.project.cashiersaas.dto.response.FnbCatalogCopyResponse;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.CategoryEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.OptionGroupEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductOptionEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductOptionGroupEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryItemEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.RecipeLineEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbCatalogRepository;
import org.lite.project.cashiersaas.repository.FnbInventoryRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.repository.OutletRepository;
import org.lite.project.cashiersaas.service.FnbCatalogCopyService;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Owner setup copy of one outlet's menu, option groups, inventory item masters and recipes into an empty outlet. */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class FnbCatalogCopyServiceTest {
    private static final UUID LOW_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID HIGH_ID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    @Mock private MerchantAccessGuard guard;
    @Mock private OutletRepository outlets;
    @Mock private FnbOrderRepository orders;
    @Mock private FnbCatalogRepository catalog;
    @Mock private FnbInventoryRepository inventory;
    @Mock private AuditLogRepository audit;
    @InjectMocks private FnbCatalogCopyService service;

    private final UUID tenantId = UUID.randomUUID();
    private final TenantPrincipal owner = new TenantPrincipal(UUID.randomUUID(), tenantId, UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), Set.of(Role.OWNERMERCHANT));
    private final TenantEntity tenant = withId(new TenantEntity(), tenantId);
    private final OutletEntity target = outlet("Target", LOW_ID);
    private final OutletEntity source = outlet("Source", HIGH_ID);

    private OutletEntity outlet(String name, UUID id) {
        return withId(OutletEntity.open(tenant, name, "Asia/Jakarta", null, null), id);
    }

    private static <T> T withId(T entity, UUID id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    private static <T> T withId(T entity) {
        return withId(entity, UUID.randomUUID());
    }

    private void stubGuard() {
        when(guard.requireOwnerIgnoringAllowance(owner, owner.getOutletId()))
                .thenReturn(new MerchantAccessContext(tenant, target, TenantMembershipEntity.grantOwner(null, tenant)));
    }

    private FnbCatalogCopyRequest requestFrom(UUID sourceOutletId) {
        FnbCatalogCopyRequest request = new FnbCatalogCopyRequest();
        request.setSourceOutletId(sourceOutletId);
        return request;
    }

    private void stubBothOutlets() {
        when(outlets.findInTenant(tenantId, target.getId())).thenReturn(Optional.of(target));
        when(outlets.findInTenant(tenantId, source.getId())).thenReturn(Optional.of(source));
    }

    @Test
    void sourceAndTargetMustDiffer() {
        stubGuard();
        when(outlets.findInTenant(tenantId, target.getId())).thenReturn(Optional.of(target));

        assertThrows(InvalidOperationException.class,
                () -> service.copy(owner, target.getId(), requestFrom(target.getId())));

        verifyNoInteractions(orders, catalog, inventory, audit);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void anOutletOutsideTheTenantIsNotFound(boolean targetMissing) {
        stubGuard();
        if (targetMissing) {
            when(outlets.findInTenant(tenantId, target.getId())).thenReturn(Optional.empty());
        } else {
            when(outlets.findInTenant(tenantId, target.getId())).thenReturn(Optional.of(target));
            when(outlets.findInTenant(tenantId, source.getId())).thenReturn(Optional.empty());
        }

        assertThrows(ResourceNotFoundException.class,
                () -> service.copy(owner, target.getId(), requestFrom(source.getId())));

        verifyNoInteractions(orders, catalog, inventory, audit);
    }

    @Test
    void anInactiveTargetIsAConflict() {
        stubGuard();
        stubBothOutlets();
        target.deactivate();

        assertThrows(OperationConflictException.class,
                () -> service.copy(owner, target.getId(), requestFrom(source.getId())));

        verifyNoInteractions(orders, catalog, inventory, audit);
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void aTargetThatAlreadyHasMenuOrIngredientsIsAConflictAndCopiesNothing(boolean hasProductsOrGroups) {
        stubGuard();
        stubBothOutlets();
        when(catalog.hasProductsOrGroups(tenantId, target.getId())).thenReturn(hasProductsOrGroups);
        if (!hasProductsOrGroups) when(inventory.hasItems(tenantId, target.getId())).thenReturn(true);

        assertThrows(OperationConflictException.class,
                () -> service.copy(owner, target.getId(), requestFrom(source.getId())));

        verify(catalog, never()).persist(any(CategoryEntity.class));
        verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void bothOutletsAreLockedLowestIdFirstBeforeTheEmptinessCheck(boolean targetHasLowerId) {
        OutletEntity lower = targetHasLowerId ? target : source;
        ReflectionTestUtils.setField(lower, "id", LOW_ID);
        ReflectionTestUtils.setField(lower == target ? source : target, "id", HIGH_ID);
        stubGuard();
        stubBothOutlets();
        when(catalog.hasProductsOrGroups(tenantId, target.getId())).thenReturn(true);

        assertThrows(OperationConflictException.class,
                () -> service.copy(owner, target.getId(), requestFrom(source.getId())));

        InOrder order = inOrder(orders, catalog);
        order.verify(orders).lockOutlet(tenantId, LOW_ID);
        order.verify(orders).lockOutlet(tenantId, HIGH_ID);
        order.verify(catalog).hasProductsOrGroups(tenantId, target.getId());
    }

    @Test
    void copiesMenuGroupsItemsAndRecipesIntoTheTargetReusingMatchingCategoriesAndAuditsOnce() {
        stubGuard();
        stubBothOutlets();
        CategoryEntity existing = withId(CategoryEntity.create(tenant, target, "  coffee ", 1));
        CategoryEntity coffee = withId(CategoryEntity.create(tenant, source, "Coffee", 1));
        CategoryEntity snack = withId(CategoryEntity.create(tenant, source, "Snack", 2));
        ProductEntity latte = withId(ProductEntity.create(tenant, source,
                new ProductEntity.Details(coffee, "Latte", null, new BigDecimal("25000.00"), null, true, 0)));
        OptionGroupEntity topping = withId(OptionGroupEntity.create(tenant, source,
                new OptionGroupEntity.Details("Topping", false, 0, 2, 0)));
        ProductOptionEntity boba = withId(ProductOptionEntity.create(tenant, source, topping, "Boba",
                new BigDecimal("3000.00"), true, 0));
        ProductOptionGroupEntity link = ProductOptionGroupEntity.create(tenant, source, latte, topping, 0);
        InventoryItemEntity beans = withId(InventoryItemEntity.create(tenant, source, null, "Biji Kopi", "g", BigDecimal.TEN));
        InventoryItemEntity inactiveMilk = withId(InventoryItemEntity.create(tenant, source, null, "Susu", "ml", BigDecimal.TEN));
        when(catalog.allCategories(tenantId, target.getId())).thenReturn(List.of(existing));
        when(catalog.categories(tenantId, source.getId())).thenReturn(List.of(coffee, snack));
        when(catalog.products(tenantId, source.getId())).thenReturn(List.of(latte));
        when(catalog.outletGroups(tenantId, source.getId())).thenReturn(List.of(topping));
        when(catalog.optionsOfGroups(tenantId, source.getId(), List.of(topping.getId()))).thenReturn(List.of(boba));
        when(catalog.productGroupLinks(tenantId, source.getId())).thenReturn(List.of(link));
        when(inventory.items(tenantId, source.getId())).thenReturn(List.of(beans));
        when(inventory.productRecipes(tenantId, source.getId(), List.of(latte.getId()))).thenReturn(List.of(
                RecipeLineEntity.forProduct(tenant, source, latte, beans, new BigDecimal("18")),
                RecipeLineEntity.forProduct(tenant, source, latte, inactiveMilk, new BigDecimal("100"))));

        FnbCatalogCopyResponse response = service.copy(owner, target.getId(), requestFrom(source.getId()));

        assertEquals(1, response.getCategoriesCreated());
        assertEquals(1, response.getCategoriesReused());
        assertEquals(1, response.getProducts());
        assertEquals(1, response.getOptionGroups());
        assertEquals(1, response.getOptions());
        assertEquals(1, response.getInventoryItems());
        assertEquals(1, response.getRecipeLines());
        assertEquals(1, response.getRecipeLinesSkipped());
        ArgumentCaptor<ProductEntity> product = ArgumentCaptor.forClass(ProductEntity.class);
        verify(catalog).persist(product.capture());
        assertSame(target, product.getValue().getOutlet());
        assertSame(existing, product.getValue().getCategory());
        ArgumentCaptor<AuditLogEntity> log = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(audit).persist(log.capture());
        assertEquals("CATALOG_COPIED", log.getValue().getAction());
        assertEquals(target.getId(), log.getValue().getEntityId());
    }
}
