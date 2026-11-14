package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.FnbOptionGroupRequest;
import org.lite.project.cashiersaas.dto.request.FnbOptionRequest;
import org.lite.project.cashiersaas.dto.request.FnbProductOptionGroupsRequest;
import org.lite.project.cashiersaas.dto.response.FnbOptionGroupResponse;
import org.lite.project.cashiersaas.dto.response.FnbOptionResponse;
import org.lite.project.cashiersaas.entity.shared.catalog.CategoryEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.OptionGroupEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductOptionEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductOptionGroupEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbCatalogRepository;
import org.lite.project.cashiersaas.repository.FnbInventoryRepository;
import org.lite.project.cashiersaas.repository.MerchantReferenceRepository;
import org.lite.project.cashiersaas.service.FnbCatalogService;
import org.lite.project.cashiersaas.service.MerchantAccessGuard;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Reusable (outlet-level) option group use cases of {@link FnbCatalogService} (TASK-064). */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class FnbOptionGroupServiceTest {
    private static final String IN_USE_MESSAGE = "Grup pilihan masih dipakai produk.";
    private static final String DUPLICATE_MESSAGE = "Grup pilihan tidak boleh duplikat.";

    @Mock private MerchantAccessGuard guard;
    @Mock private FnbCatalogRepository catalog;
    @Mock private MerchantReferenceRepository references;
    @Mock private AuditLogRepository audit;
    @Mock private FnbInventoryRepository inventory;
    @InjectMocks private FnbCatalogService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID outletId = UUID.randomUUID();
    private final UUID categoryId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID groupAId = UUID.randomUUID();
    private final UUID groupBId = UUID.randomUUID();
    private final UUID optionId = UUID.randomUUID();
    private final TenantPrincipal owner = new TenantPrincipal(userId, tenantId, outletId,
            UUID.randomUUID(), UUID.randomUUID(), Set.of(Role.OWNERMERCHANT));
    private final TenantPrincipal cashier = new TenantPrincipal(UUID.randomUUID(), tenantId, outletId,
            UUID.randomUUID(), UUID.randomUUID(), Set.of(Role.CASHIER));
    private final TenantEntity tenant = new TenantEntity();
    private final OutletEntity outlet = OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null);
    private final MerchantAccessContext context = new MerchantAccessContext(tenant, outlet, null);

    private CategoryEntity category;
    private ProductEntity product;
    private OptionGroupEntity groupA;
    private OptionGroupEntity groupB;
    private ProductOptionEntity option;

    @BeforeEach
    void setUp() {
        category = CategoryEntity.create(tenant, outlet, "Coffee", 1);
        ReflectionTestUtils.setField(category, "id", categoryId);
        product = ProductEntity.create(tenant, outlet,
                new ProductEntity.Details(category, "Latte", null, new BigDecimal("10000.00"), null, true, 0));
        ReflectionTestUtils.setField(product, "id", productId);
        groupA = group(groupAId, "Topping", 0, 2, false, 0);
        groupB = group(groupBId, "Size", 1, 1, true, 1);
        option = ProductOptionEntity.create(tenant, outlet, groupA, "Boba", new BigDecimal("3000.00"), true, 0);
        ReflectionTestUtils.setField(option, "id", optionId);
    }

    // ---------------------------------------------------------------- 1. createGroup

    @Test
    void createGroupUsesOwnerGuardAndPersistsOutletLevelGroupWithTrimmedName() {
        stubOwner();

        FnbOptionGroupResponse response = service.createGroup(owner, outletId,
                groupRequest("  Topping  ", false, 0, 3, 2));

        verify(guard).requireOwner(owner, outletId);
        ArgumentCaptor<OptionGroupEntity> captor = ArgumentCaptor.forClass(OptionGroupEntity.class);
        verify(catalog).persist(captor.capture());
        OptionGroupEntity saved = captor.getValue();
        assertEquals("Topping", saved.getName());
        assertFalse(saved.isRequired());
        assertEquals(0, saved.getMinimumSelections());
        assertEquals(3, saved.getMaximumSelections());
        assertEquals(2, saved.getDisplayOrder());
        assertFalse(saved.isArchived());
        assertSame(tenant, saved.getTenant());
        assertSame(outlet, saved.getOutlet());
        assertEquals("Topping", response.getName());
        assertEquals(3, response.getMaximumSelections());
    }

    @Test
    void createGroupAcceptsRequiredGroupWithMinimumOne() {
        stubOwner();

        FnbOptionGroupResponse response = service.createGroup(owner, outletId, groupRequest("Size", true, 1, 1, 0));

        verify(catalog).persist(any(OptionGroupEntity.class));
        assertTrue(response.isRequired());
        assertEquals(1, response.getMinimumSelections());
    }

    @ParameterizedTest
    @CsvSource({"Topping,true,3,2", "Size,true,0,1", "Topping,false,1,2"})
    void createGroupRejectsInvalidSelectionLimits(String name, boolean required, int minimum, int maximum) {
        stubOwner();

        assertThrows(InvalidOperationException.class,
                () -> service.createGroup(owner, outletId, groupRequest(name, required, minimum, maximum, 0)));

        verify(catalog, never()).persist(any(OptionGroupEntity.class));
    }

    @Test
    void updateGroupRejectsInvalidLimitsAndLeavesGroupUntouched() {
        stubOwner();
        lenient().when(catalog.group(tenantId, outletId, groupAId)).thenReturn(Optional.of(groupA));

        assertThrows(InvalidOperationException.class,
                () -> service.updateGroup(owner, outletId, groupAId, groupRequest("Renamed", true, 0, 2, 5)));

        assertEquals("Topping", groupA.getName());
        assertFalse(groupA.isRequired());
        assertEquals(0, groupA.getDisplayOrder());
    }

    // ---------------------------------------------------------------- 2. update / archive unknown id

    @Test
    void updateGroupUnknownIdIsNotFound() {
        stubOwner();
        when(catalog.group(tenantId, outletId, groupAId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.updateGroup(owner, outletId, groupAId, groupRequest("Topping", false, 0, 2, 0)));
    }

    @Test
    void updateGroupAppliesDetailsWithTrimmedNameWithoutProductLookup() {
        stubOwner();
        stubGroup(groupA);

        FnbOptionGroupResponse response = service.updateGroup(owner, outletId, groupAId,
                groupRequest("  Topping Baru ", true, 1, 3, 4));

        assertEquals("Topping Baru", groupA.getName());
        assertTrue(groupA.isRequired());
        assertEquals(1, groupA.getMinimumSelections());
        assertEquals(3, groupA.getMaximumSelections());
        assertEquals(4, groupA.getDisplayOrder());
        assertEquals(groupAId, response.getId());
        assertEquals("Topping Baru", response.getName());
    }

    @Test
    void archiveGroupUnknownIdIsNotFoundAndNeverChecksUsage() {
        stubOwner();
        when(catalog.group(tenantId, outletId, groupAId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.archiveGroup(owner, outletId, groupAId));

        verify(catalog, never()).groupInUse(any(UUID.class), any(UUID.class), any(UUID.class));
    }

    // ---------------------------------------------------------------- 3. archiveGroup in use

    @Test
    void archiveGroupStillAttachedToAProductIsConflictAndGroupStaysActive() {
        stubOwner();
        stubGroup(groupA);
        when(catalog.groupInUse(tenantId, outletId, groupAId)).thenReturn(true);

        OperationConflictException error = assertThrows(OperationConflictException.class,
                () -> service.archiveGroup(owner, outletId, groupAId));

        assertEquals(IN_USE_MESSAGE, error.getMessage());
        assertFalse(groupA.isArchived());
    }

    @Test
    void archiveGroupNotInUseArchivesItAndReturnsTheGroup() {
        stubOwner();
        stubGroup(groupA);
        when(catalog.groupInUse(tenantId, outletId, groupAId)).thenReturn(false);

        FnbOptionGroupResponse response = service.archiveGroup(owner, outletId, groupAId);

        assertTrue(groupA.isArchived());
        assertEquals(groupAId, response.getId());
        verify(catalog).groupInUse(tenantId, outletId, groupAId);
    }

    // ---------------------------------------------------------------- 4. replaceProductGroups

    @Test
    void replaceProductGroupsClearsFirstThenPersistsOneLinkPerIdWithDisplayOrderEqualToIndex() {
        stubOwner();
        stubProduct();
        stubGroup(groupA);
        stubGroup(groupB);

        service.replaceProductGroups(owner, outletId, productId, groupsRequest(groupBId, groupAId));

        InOrder order = inOrder(catalog);
        order.verify(catalog).deleteProductGroups(tenantId, outletId, productId);
        order.verify(catalog, times(2)).persist(any(ProductOptionGroupEntity.class));
        ArgumentCaptor<ProductOptionGroupEntity> captor = ArgumentCaptor.forClass(ProductOptionGroupEntity.class);
        verify(catalog, times(2)).persist(captor.capture());
        List<ProductOptionGroupEntity> links = captor.getAllValues();
        assertSame(groupB, links.get(0).getOptionGroup());
        assertEquals(0, links.get(0).getDisplayOrder());
        assertSame(groupA, links.get(1).getOptionGroup());
        assertEquals(1, links.get(1).getDisplayOrder());
        for (ProductOptionGroupEntity link : links) {
            assertSame(product, link.getProduct());
            assertSame(tenant, link.getTenant());
            assertSame(outlet, link.getOutlet());
        }
    }

    @Test
    void replaceProductGroupsReturnsGroupsInRequestOrder() {
        stubOwner();
        stubProduct();
        stubGroup(groupA);
        stubGroup(groupB);

        List<FnbOptionGroupResponse> response = service.replaceProductGroups(owner, outletId, productId,
                groupsRequest(groupBId, groupAId));

        assertEquals(2, response.size());
        assertEquals(groupBId, response.get(0).getId());
        assertEquals("Size", response.get(0).getName());
        assertEquals(groupAId, response.get(1).getId());
        assertEquals("Topping", response.get(1).getName());
    }

    @Test
    void replaceProductGroupsRejectsDuplicateIdsAndNothingIsDeletedOrPersisted() {
        stubOwner();
        lenient().when(catalog.product(tenantId, outletId, productId)).thenReturn(Optional.of(product));
        lenient().when(catalog.group(tenantId, outletId, groupAId)).thenReturn(Optional.of(groupA));

        InvalidOperationException error = assertThrows(InvalidOperationException.class,
                () -> service.replaceProductGroups(owner, outletId, productId, groupsRequest(groupAId, groupAId)));

        assertEquals(DUPLICATE_MESSAGE, error.getMessage());
        verifyNothingWrittenForProductGroups();
    }

    @Test
    void replaceProductGroupsRejectsUnknownGroupIdAndNothingIsDeletedOrPersisted() {
        stubOwner();
        stubProduct();
        stubGroup(groupA);
        when(catalog.group(tenantId, outletId, groupBId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.replaceProductGroups(owner, outletId, productId, groupsRequest(groupAId, groupBId)));

        verifyNothingWrittenForProductGroups();
    }

    @Test
    void replaceProductGroupsWithEmptyListOnlyClearsTheProductLinks() {
        stubOwner();
        stubProduct();

        List<FnbOptionGroupResponse> response = service.replaceProductGroups(owner, outletId, productId,
                groupsRequest());

        verify(catalog).deleteProductGroups(tenantId, outletId, productId);
        verify(catalog, never()).persist(any(ProductOptionGroupEntity.class));
        verify(catalog, never()).group(any(UUID.class), any(UUID.class), any(UUID.class));
        assertTrue(response.isEmpty());
    }

    @Test
    void replaceProductGroupsRejectsUnknownProductWithoutDeleting() {
        stubOwner();
        when(catalog.product(tenantId, outletId, productId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.replaceProductGroups(owner, outletId, productId, groupsRequest(groupAId)));

        verifyNothingWrittenForProductGroups();
    }

    // ---------------------------------------------------------------- 5. roles

    private interface Call {
        void run(FnbOptionGroupServiceTest test);
    }

    static Stream<Arguments> cashierDeniedCalls() {
        return Stream.of(
                Arguments.of(Named.of("createGroup", (Call) t -> t.service.createGroup(
                        t.cashier, t.outletId, t.groupRequest("Topping", false, 0, 2, 0)))),
                Arguments.of(Named.of("updateGroup", (Call) t -> t.service.updateGroup(
                        t.cashier, t.outletId, t.groupAId, t.groupRequest("Topping", false, 0, 2, 0)))),
                Arguments.of(Named.of("archiveGroup", (Call) t -> t.service.archiveGroup(
                        t.cashier, t.outletId, t.groupAId))),
                Arguments.of(Named.of("replaceProductGroups", (Call) t -> t.service.replaceProductGroups(
                        t.cashier, t.outletId, t.productId, t.groupsRequest(t.groupAId)))));
    }

    @ParameterizedTest
    @MethodSource("cashierDeniedCalls")
    void cashierCannotUseOwnerOnlyGroupUseCases(Call call) {
        when(guard.requireOwner(cashier, outletId)).thenThrow(new ResourceNotFoundException());

        assertThrows(ResourceNotFoundException.class, () -> call.run(this));

        verify(guard).requireOwner(cashier, outletId);
        verifyNoInteractions(catalog, inventory, audit, references);
        assertEquals("Topping", groupA.getName());
        assertFalse(groupA.isArchived());
    }

    @Test
    void groupsUsesOutletGuardAndReturnsTheLibraryInRepositoryOrder() {
        when(guard.requireOutlet(cashier, outletId)).thenReturn(context);
        when(catalog.outletGroups(tenantId, outletId)).thenReturn(List.of(groupB, groupA));

        List<FnbOptionGroupResponse> response = service.groups(cashier, outletId);

        verify(guard).requireOutlet(cashier, outletId);
        verify(guard, never()).requireOwner(any(TenantPrincipal.class), any(UUID.class));
        assertEquals(2, response.size());
        assertEquals(groupBId, response.get(0).getId());
        assertEquals(groupAId, response.get(1).getId());
        assertTrue(response.get(0).isRequired());
        assertEquals(1, response.get(0).getMinimumSelections());
        assertEquals(1, response.get(0).getMaximumSelections());
    }

    @Test
    void productGroupsUsesOutletGuardChecksProductAndReturnsAttachedGroups() {
        when(guard.requireOutlet(cashier, outletId)).thenReturn(context);
        stubProduct();
        when(catalog.groups(tenantId, outletId, productId)).thenReturn(List.of(groupB, groupA));

        List<FnbOptionGroupResponse> response = service.productGroups(cashier, outletId, productId);

        verify(guard).requireOutlet(cashier, outletId);
        verify(guard, never()).requireOwner(any(TenantPrincipal.class), any(UUID.class));
        assertEquals(2, response.size());
        assertEquals(groupBId, response.get(0).getId());
        assertEquals(groupAId, response.get(1).getId());
    }

    @Test
    void productGroupsUnknownProductIsNotFound() {
        when(guard.requireOutlet(cashier, outletId)).thenReturn(context);
        when(catalog.product(tenantId, outletId, productId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.productGroups(cashier, outletId, productId));

        verify(catalog, never()).groups(any(UUID.class), any(UUID.class), any(UUID.class));
    }

    // ---------------------------------------------------------------- 6. options resolve the group by id only

    @Test
    void optionsResolveTheGroupByIdOnlyAndReturnItsOptions() {
        when(guard.requireOutlet(cashier, outletId)).thenReturn(context);
        stubGroup(groupA);
        when(catalog.options(tenantId, outletId, groupAId)).thenReturn(List.of(option));

        List<FnbOptionResponse> response = service.options(cashier, outletId, groupAId);

        assertEquals(1, response.size());
        assertEquals(optionId, response.get(0).getId());
        assertEquals(groupAId, response.get(0).getOptionGroupId());
        assertEquals("Boba", response.get(0).getName());
    }

    @Test
    void optionsOfAnUnknownOrArchivedGroupAreNotFound() {
        when(guard.requireOutlet(cashier, outletId)).thenReturn(context);
        when(catalog.group(tenantId, outletId, groupAId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.options(cashier, outletId, groupAId));

        verify(catalog, never()).options(any(UUID.class), any(UUID.class), any(UUID.class));
    }

    @Test
    void createOptionResolvesTheGroupByIdOnlyAndPersistsWithTrimmedName() {
        stubOwner();
        stubGroup(groupA);

        FnbOptionResponse response = service.createOption(owner, outletId, groupAId,
                optionRequest("  Jelly  ", "2000.00", null));

        ArgumentCaptor<ProductOptionEntity> captor = ArgumentCaptor.forClass(ProductOptionEntity.class);
        verify(catalog).persist(captor.capture());
        ProductOptionEntity saved = captor.getValue();
        assertEquals("Jelly", saved.getName());
        assertSame(groupA, saved.getOptionGroup());
        assertSame(tenant, saved.getTenant());
        assertSame(outlet, saved.getOutlet());
        assertEquals(0, new BigDecimal("2000.00").compareTo(saved.getPriceAdjustment()));
        assertEquals(groupAId, response.getOptionGroupId());
    }

    @Test
    void createOptionInAnUnknownOrArchivedGroupIsNotFoundAndNothingIsPersisted() {
        stubOwner();
        when(catalog.group(tenantId, outletId, groupAId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.createOption(owner, outletId, groupAId, optionRequest("Jelly", "2000.00", null)));

        verify(catalog, never()).persist(any(ProductOptionEntity.class));
    }

    // ---------------------------------------------------------------- helpers

    private void stubOwner() {
        when(guard.requireOwner(owner, outletId)).thenReturn(context);
    }

    private void stubProduct() {
        when(catalog.product(tenantId, outletId, productId)).thenReturn(Optional.of(product));
    }

    private void stubGroup(OptionGroupEntity entity) {
        when(catalog.group(tenantId, outletId, entity.getId())).thenReturn(Optional.of(entity));
    }

    private void verifyNothingWrittenForProductGroups() {
        verify(catalog, never()).deleteProductGroups(any(UUID.class), any(UUID.class), any(UUID.class));
        verify(catalog, never()).persist(any(ProductOptionGroupEntity.class));
    }

    private OptionGroupEntity group(UUID id, String name, int minimum, int maximum, boolean required, int displayOrder) {
        OptionGroupEntity entity = OptionGroupEntity.create(tenant, outlet,
                new OptionGroupEntity.Details(name, required, minimum, maximum, displayOrder));
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    private FnbOptionGroupRequest groupRequest(String name, boolean required, int minimum, int maximum, int displayOrder) {
        FnbOptionGroupRequest request = new FnbOptionGroupRequest();
        request.setName(name);
        request.setRequired(required);
        request.setMinimumSelections(minimum);
        request.setMaximumSelections(maximum);
        request.setDisplayOrder(displayOrder);
        return request;
    }

    private FnbProductOptionGroupsRequest groupsRequest(UUID... ids) {
        FnbProductOptionGroupsRequest request = new FnbProductOptionGroupsRequest();
        request.setGroupIds(new ArrayList<>(Arrays.asList(ids)));
        return request;
    }

    private FnbOptionRequest optionRequest(String name, String priceAdjustment, String reason) {
        FnbOptionRequest request = new FnbOptionRequest();
        request.setName(name);
        request.setPriceAdjustment(new BigDecimal(priceAdjustment));
        request.setAvailable(true);
        request.setDisplayOrder(0);
        request.setPriceChangeReason(reason);
        return request;
    }
}
