package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.FnbOptionRequest;
import org.lite.project.cashiersaas.dto.request.FnbProductRequest;
import org.lite.project.cashiersaas.dto.request.FnbRecipeRequest;
import org.lite.project.cashiersaas.dto.response.FnbOptionResponse;
import org.lite.project.cashiersaas.dto.response.FnbProductResponse;
import org.lite.project.cashiersaas.dto.response.FnbRecipeResponse;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.CategoryEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.OptionGroupEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductOptionEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryItemEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.RecipeLineEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class FnbCatalogRecipeServiceTest {
    private static final String PRICE_REASON_MESSAGE = "Alasan perubahan harga wajib diisi.";

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
    private final UUID groupId = UUID.randomUUID();
    private final UUID optionId = UUID.randomUUID();
    private final UUID itemAId = UUID.randomUUID();
    private final UUID itemBId = UUID.randomUUID();
    private final TenantPrincipal owner = new TenantPrincipal(userId, tenantId, outletId,
            UUID.randomUUID(), UUID.randomUUID(), Set.of(Role.OWNERMERCHANT));
    private final TenantEntity tenant = new TenantEntity();
    private final OutletEntity outlet = OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null);
    private final StaffUserEntity user = new StaffUserEntity();
    private final MerchantAccessContext context = new MerchantAccessContext(tenant, outlet, null);

    private CategoryEntity category;
    private ProductEntity product;
    private OptionGroupEntity group;
    private ProductOptionEntity option;
    private InventoryItemEntity itemA;
    private InventoryItemEntity itemB;

    @BeforeEach
    void setUp() {
        category = CategoryEntity.create(tenant, outlet, "Coffee", 1);
        ReflectionTestUtils.setField(category, "id", categoryId);
        product = ProductEntity.create(tenant, outlet, productDetails(category, "10000.00"));
        ReflectionTestUtils.setField(product, "id", productId);
        group = OptionGroupEntity.create(tenant, outlet, product, new OptionGroupEntity.Details("Topping", false, 0, 2, 0));
        ReflectionTestUtils.setField(group, "id", groupId);
        option = ProductOptionEntity.create(tenant, outlet, group, "Boba", new BigDecimal("3000.00"), true, 0);
        ReflectionTestUtils.setField(option, "id", optionId);
        itemA = inventoryItem(itemAId, "Kopi", "g");
        itemB = inventoryItem(itemBId, "Susu", "ml");
    }

    // ---------------------------------------------------------------- replaceProductRecipe

    @Test
    void replaceProductRecipeRequiresOwnerGuardAndTouchesNothingWhenDenied() {
        when(guard.requireOwner(owner, outletId)).thenThrow(new ResourceNotFoundException());

        assertThrows(ResourceNotFoundException.class,
                () -> service.replaceProductRecipe(owner, outletId, productId, recipeRequest(line(itemAId, "18"))));

        verify(guard).requireOwner(owner, outletId);
        verifyNoInteractions(catalog, inventory, audit, references);
    }

    @Test
    void replaceProductRecipeRejectsMissingProductWithoutDeleting() {
        stubOwner();
        when(catalog.product(tenantId, outletId, productId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.replaceProductRecipe(owner, outletId, productId, recipeRequest(line(itemAId, "18"))));

        verifyNoInteractions(inventory, audit);
    }

    @Test
    void replaceProductRecipeRejectsProductWhoseCategoryIsArchived() {
        CategoryEntity archivedCategory = CategoryEntity.create(tenant, outlet, "Old", 2);
        archivedCategory.archive();
        ProductEntity archivedProduct = ProductEntity.create(tenant, outlet, productDetails(archivedCategory, "5000.00"));
        ReflectionTestUtils.setField(archivedProduct, "id", productId);
        stubOwner();
        when(catalog.product(tenantId, outletId, productId)).thenReturn(Optional.of(archivedProduct));

        assertThrows(ResourceNotFoundException.class,
                () -> service.replaceProductRecipe(owner, outletId, productId, recipeRequest(line(itemAId, "18"))));

        verifyNoInteractions(inventory, audit);
    }

    @Test
    void replaceProductRecipeRejectsDuplicateIngredientBeforeAnyDelete() {
        stubOwner();
        stubProduct();
        stubItem(itemA);

        InvalidOperationException error = assertThrows(InvalidOperationException.class,
                () -> service.replaceProductRecipe(owner, outletId, productId,
                        recipeRequest(line(itemAId, "18"), line(itemAId, "5"))));

        assertEquals("Bahan resep tidak boleh duplikat.", error.getMessage());
        verifyNothingWrittenForRecipe();
    }

    @Test
    void replaceProductRecipeRejectsUnknownOrInactiveIngredientBeforeAnyDelete() {
        stubOwner();
        stubProduct();
        stubItem(itemA);
        when(inventory.item(tenantId, outletId, itemBId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.replaceProductRecipe(owner, outletId, productId,
                        recipeRequest(line(itemAId, "18"), line(itemBId, "120"))));

        verifyNothingWrittenForRecipe();
    }

    @Test
    void replaceProductRecipeDeletesOldLinesThenPersistsNewLinesThenAudits() {
        arrangeProductRecipeHappyPath();

        service.replaceProductRecipe(owner, outletId, productId,
                recipeRequest(line(itemAId, "18"), line(itemBId, "120.5")));

        InOrder order = inOrder(inventory, audit);
        order.verify(inventory).deleteProductRecipe(tenantId, outletId, productId);
        order.verify(inventory, times(2)).persist(any(RecipeLineEntity.class));
        order.verify(audit).persist(any(AuditLogEntity.class));
    }

    @Test
    void replaceProductRecipePersistsProductLinesWithItemAndQuantityInRequestOrder() {
        arrangeProductRecipeHappyPath();

        service.replaceProductRecipe(owner, outletId, productId,
                recipeRequest(line(itemAId, "18"), line(itemBId, "120.5")));

        ArgumentCaptor<RecipeLineEntity> captor = ArgumentCaptor.forClass(RecipeLineEntity.class);
        verify(inventory, times(2)).persist(captor.capture());
        List<RecipeLineEntity> lines = captor.getAllValues();
        assertSame(itemA, lines.get(0).getInventoryItem());
        assertEquals(0, new BigDecimal("18").compareTo(lines.get(0).getQuantityPerUnit()));
        assertSame(itemB, lines.get(1).getInventoryItem());
        assertEquals(0, new BigDecimal("120.5").compareTo(lines.get(1).getQuantityPerUnit()));
        for (RecipeLineEntity line : lines) {
            assertSame(product, line.getProduct());
            assertNull(line.getProductOption());
            assertSame(tenant, line.getTenant());
            assertSame(outlet, line.getOutlet());
        }
    }

    @Test
    void replaceProductRecipeRecordsRecipeAuditWithProductIdNoReasonAndLineCount() {
        arrangeProductRecipeHappyPath();

        service.replaceProductRecipe(owner, outletId, productId,
                recipeRequest(line(itemAId, "18"), line(itemBId, "120.5")));

        AuditLogEntity log = captureAudit();
        assertEquals("FNB_RECIPE_CHANGED", log.getAction());
        assertEquals("RECIPE", log.getEntityType());
        assertEquals(productId, log.getEntityId());
        assertNull(log.getReason());
        assertEquals("lines=2", log.getDetails());
        assertSame(tenant, log.getTenant());
        assertSame(outlet, log.getOutlet());
        assertSame(user, log.getActor());
    }

    @Test
    void replaceProductRecipeReturnsMappedLinesWithNameUnitAndQuantity() {
        arrangeProductRecipeHappyPath();

        FnbRecipeResponse response = service.replaceProductRecipe(owner, outletId, productId,
                recipeRequest(line(itemAId, "18"), line(itemBId, "120.5")));

        assertEquals(2, response.getLines().size());
        FnbRecipeResponse.Line first = response.getLines().get(0);
        assertEquals(itemAId, first.getInventoryItemId());
        assertEquals("Kopi", first.getInventoryItemName());
        assertEquals("g", first.getUnit());
        assertEquals(0, new BigDecimal("18").compareTo(first.getQuantityPerUnit()));
        FnbRecipeResponse.Line second = response.getLines().get(1);
        assertEquals(itemBId, second.getInventoryItemId());
        assertEquals("Susu", second.getInventoryItemName());
        assertEquals("ml", second.getUnit());
        assertEquals(0, new BigDecimal("120.5").compareTo(second.getQuantityPerUnit()));
    }

    @Test
    void replaceProductRecipeUsesProductVariantOfDeleteOnly() {
        arrangeProductRecipeHappyPath();

        service.replaceProductRecipe(owner, outletId, productId,
                recipeRequest(line(itemAId, "18"), line(itemBId, "120.5")));

        verify(inventory).deleteProductRecipe(tenantId, outletId, productId);
        verify(inventory, never()).deleteOptionRecipe(any(UUID.class), any(UUID.class), any(UUID.class));
    }

    @Test
    void replaceProductRecipeWithEmptyListClearsRecipeAndAuditsZeroLines() {
        stubOwner();
        stubProduct();
        stubAuditReferences();

        FnbRecipeResponse response = service.replaceProductRecipe(owner, outletId, productId, recipeRequest());

        verify(inventory).deleteProductRecipe(tenantId, outletId, productId);
        verify(inventory, never()).persist(any(RecipeLineEntity.class));
        verify(inventory, never()).item(any(UUID.class), any(UUID.class), any(UUID.class));
        AuditLogEntity log = captureAudit();
        assertEquals("FNB_RECIPE_CHANGED", log.getAction());
        assertEquals(productId, log.getEntityId());
        assertNull(log.getReason());
        assertEquals("lines=0", log.getDetails());
        assertTrue(response.getLines().isEmpty());
    }

    // ---------------------------------------------------------------- replaceOptionRecipe

    @Test
    void replaceOptionRecipeRequiresOwnerGuardAndTouchesNothingWhenDenied() {
        when(guard.requireOwner(owner, outletId)).thenThrow(new ResourceNotFoundException());

        assertThrows(ResourceNotFoundException.class,
                () -> service.replaceOptionRecipe(owner, outletId, productId, groupId, optionId,
                        recipeRequest(line(itemAId, "50"))));

        verify(guard).requireOwner(owner, outletId);
        verifyNoInteractions(catalog, inventory, audit, references);
    }

    @Test
    void replaceOptionRecipeRejectsGroupIdThatDoesNotMatchTheOptionGroup() {
        UUID otherGroupId = UUID.randomUUID();
        OptionGroupEntity otherGroup = OptionGroupEntity.create(tenant, outlet, product,
                new OptionGroupEntity.Details("Size", false, 0, 1, 1));
        ReflectionTestUtils.setField(otherGroup, "id", otherGroupId);
        stubOwner();
        stubProduct();
        when(catalog.group(tenantId, outletId, otherGroupId)).thenReturn(Optional.of(otherGroup));
        when(catalog.option(tenantId, outletId, optionId)).thenReturn(Optional.of(option));

        assertThrows(ResourceNotFoundException.class,
                () -> service.replaceOptionRecipe(owner, outletId, productId, otherGroupId, optionId,
                        recipeRequest(line(itemAId, "50"))));

        verifyNoInteractions(inventory, audit);
    }

    @Test
    void replaceOptionRecipeRejectsGroupThatBelongsToAnotherProduct() {
        UUID otherProductId = UUID.randomUUID();
        ProductEntity otherProduct = ProductEntity.create(tenant, outlet, productDetails(category, "7000.00"));
        ReflectionTestUtils.setField(otherProduct, "id", otherProductId);
        OptionGroupEntity foreignGroup = OptionGroupEntity.create(tenant, outlet, otherProduct,
                new OptionGroupEntity.Details("Topping", false, 0, 2, 0));
        ReflectionTestUtils.setField(foreignGroup, "id", groupId);
        stubOwner();
        stubProduct();
        when(catalog.group(tenantId, outletId, groupId)).thenReturn(Optional.of(foreignGroup));

        assertThrows(ResourceNotFoundException.class,
                () -> service.replaceOptionRecipe(owner, outletId, productId, groupId, optionId,
                        recipeRequest(line(itemAId, "50"))));

        verifyNoInteractions(inventory, audit);
    }

    @Test
    void replaceOptionRecipeRejectsMissingOption() {
        stubOwner();
        stubProduct();
        stubGroup();
        when(catalog.option(tenantId, outletId, optionId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.replaceOptionRecipe(owner, outletId, productId, groupId, optionId,
                        recipeRequest(line(itemAId, "50"))));

        verifyNoInteractions(inventory, audit);
    }

    @Test
    void replaceOptionRecipeRejectsMissingProduct() {
        stubOwner();
        when(catalog.product(tenantId, outletId, productId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.replaceOptionRecipe(owner, outletId, productId, groupId, optionId,
                        recipeRequest(line(itemAId, "50"))));

        verifyNoInteractions(inventory, audit);
    }

    @Test
    void replaceOptionRecipeRejectsDuplicateIngredientBeforeAnyDelete() {
        stubOwner();
        stubOption();
        stubItem(itemA);

        assertThrows(InvalidOperationException.class,
                () -> service.replaceOptionRecipe(owner, outletId, productId, groupId, optionId,
                        recipeRequest(line(itemAId, "50"), line(itemAId, "10"))));

        verifyNothingWrittenForRecipe();
    }

    @Test
    void replaceOptionRecipeRejectsUnknownIngredientBeforeAnyDelete() {
        stubOwner();
        stubOption();
        when(inventory.item(tenantId, outletId, itemAId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.replaceOptionRecipe(owner, outletId, productId, groupId, optionId,
                        recipeRequest(line(itemAId, "50"))));

        verifyNothingWrittenForRecipe();
    }

    @Test
    void replaceOptionRecipeDeletesOptionLinesThenPersistsOptionLinesThenAudits() {
        arrangeOptionRecipeHappyPath();

        service.replaceOptionRecipe(owner, outletId, productId, groupId, optionId,
                recipeRequest(line(itemAId, "50"), line(itemBId, "15")));

        InOrder order = inOrder(inventory, audit);
        order.verify(inventory).deleteOptionRecipe(tenantId, outletId, optionId);
        order.verify(inventory, times(2)).persist(any(RecipeLineEntity.class));
        order.verify(audit).persist(any(AuditLogEntity.class));
        verify(inventory, never()).deleteProductRecipe(any(UUID.class), any(UUID.class), any(UUID.class));
    }

    @Test
    void replaceOptionRecipePersistsOptionLinesNotProductLines() {
        arrangeOptionRecipeHappyPath();

        service.replaceOptionRecipe(owner, outletId, productId, groupId, optionId,
                recipeRequest(line(itemAId, "50"), line(itemBId, "15")));

        ArgumentCaptor<RecipeLineEntity> captor = ArgumentCaptor.forClass(RecipeLineEntity.class);
        verify(inventory, times(2)).persist(captor.capture());
        List<RecipeLineEntity> lines = captor.getAllValues();
        assertSame(itemA, lines.get(0).getInventoryItem());
        assertEquals(0, new BigDecimal("50").compareTo(lines.get(0).getQuantityPerUnit()));
        assertSame(itemB, lines.get(1).getInventoryItem());
        assertEquals(0, new BigDecimal("15").compareTo(lines.get(1).getQuantityPerUnit()));
        for (RecipeLineEntity line : lines) {
            assertSame(option, line.getProductOption());
            assertNull(line.getProduct());
            assertSame(tenant, line.getTenant());
            assertSame(outlet, line.getOutlet());
        }
    }

    @Test
    void replaceOptionRecipeAuditsWithOptionIdAndSharedRecipeFormat() {
        arrangeOptionRecipeHappyPath();

        service.replaceOptionRecipe(owner, outletId, productId, groupId, optionId,
                recipeRequest(line(itemAId, "50"), line(itemBId, "15")));

        AuditLogEntity log = captureAudit();
        assertEquals("FNB_RECIPE_CHANGED", log.getAction());
        assertEquals("RECIPE", log.getEntityType());
        assertEquals(optionId, log.getEntityId());
        assertNull(log.getReason());
        assertEquals("lines=2", log.getDetails());
        assertSame(tenant, log.getTenant());
        assertSame(outlet, log.getOutlet());
        assertSame(user, log.getActor());
    }

    @Test
    void replaceOptionRecipeReturnsMappedLines() {
        arrangeOptionRecipeHappyPath();

        FnbRecipeResponse response = service.replaceOptionRecipe(owner, outletId, productId, groupId, optionId,
                recipeRequest(line(itemAId, "50"), line(itemBId, "15")));

        assertEquals(2, response.getLines().size());
        assertEquals("Kopi", response.getLines().get(0).getInventoryItemName());
        assertEquals("g", response.getLines().get(0).getUnit());
        assertEquals("Susu", response.getLines().get(1).getInventoryItemName());
        assertEquals("ml", response.getLines().get(1).getUnit());
    }

    @Test
    void replaceOptionRecipeWithEmptyListClearsRecipeAndAuditsZeroLines() {
        stubOwner();
        stubOption();
        stubAuditReferences();

        FnbRecipeResponse response = service.replaceOptionRecipe(owner, outletId, productId, groupId, optionId, recipeRequest());

        verify(inventory).deleteOptionRecipe(tenantId, outletId, optionId);
        verify(inventory, never()).persist(any(RecipeLineEntity.class));
        AuditLogEntity log = captureAudit();
        assertEquals(optionId, log.getEntityId());
        assertEquals("lines=0", log.getDetails());
        assertTrue(response.getLines().isEmpty());
    }

    // ---------------------------------------------------------------- productRecipe / optionRecipe (GET)

    @Test
    void productRecipeRequiresOwnerGuardAndQueriesNothingWhenDenied() {
        when(guard.requireOwner(owner, outletId)).thenThrow(new ResourceNotFoundException());

        assertThrows(ResourceNotFoundException.class, () -> service.productRecipe(owner, outletId, productId));

        verify(guard).requireOwner(owner, outletId);
        verifyNoInteractions(catalog, inventory, audit);
    }

    @Test
    void productRecipeRejectsMissingProductWithoutQueryingRecipes() {
        stubOwner();
        when(catalog.product(tenantId, outletId, productId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.productRecipe(owner, outletId, productId));

        verifyNoInteractions(inventory);
    }

    @Test
    void productRecipeRejectsProductWhoseCategoryIsArchived() {
        CategoryEntity archivedCategory = CategoryEntity.create(tenant, outlet, "Old", 2);
        archivedCategory.archive();
        ProductEntity archivedProduct = ProductEntity.create(tenant, outlet, productDetails(archivedCategory, "5000.00"));
        ReflectionTestUtils.setField(archivedProduct, "id", productId);
        stubOwner();
        when(catalog.product(tenantId, outletId, productId)).thenReturn(Optional.of(archivedProduct));

        assertThrows(ResourceNotFoundException.class, () -> service.productRecipe(owner, outletId, productId));

        verifyNoInteractions(inventory);
    }

    @Test
    void productRecipeReturnsMappedLinesFromProductRecipesQuery() {
        stubOwner();
        stubProduct();
        when(inventory.productRecipes(tenantId, outletId, List.of(productId))).thenReturn(List.of(
                RecipeLineEntity.forProduct(tenant, outlet, product, itemA, new BigDecimal("18.000")),
                RecipeLineEntity.forProduct(tenant, outlet, product, itemB, new BigDecimal("120.000"))));

        FnbRecipeResponse response = service.productRecipe(owner, outletId, productId);

        assertEquals(2, response.getLines().size());
        FnbRecipeResponse.Line first = response.getLines().get(0);
        assertEquals(itemAId, first.getInventoryItemId());
        assertEquals("Kopi", first.getInventoryItemName());
        assertEquals("g", first.getUnit());
        assertEquals(0, new BigDecimal("18").compareTo(first.getQuantityPerUnit()));
        FnbRecipeResponse.Line second = response.getLines().get(1);
        assertEquals(itemBId, second.getInventoryItemId());
        assertEquals("Susu", second.getInventoryItemName());
        assertEquals("ml", second.getUnit());
        assertEquals(0, new BigDecimal("120").compareTo(second.getQuantityPerUnit()));
        verify(inventory, never()).deleteProductRecipe(any(UUID.class), any(UUID.class), any(UUID.class));
        verify(inventory, never()).persist(any(RecipeLineEntity.class));
        verifyNoInteractions(audit);
    }

    @Test
    void productRecipeReturnsEmptyLinesWhenProductHasNoRecipe() {
        stubOwner();
        stubProduct();
        when(inventory.productRecipes(tenantId, outletId, List.of(productId))).thenReturn(List.of());

        FnbRecipeResponse response = service.productRecipe(owner, outletId, productId);

        assertTrue(response.getLines().isEmpty());
    }

    @Test
    void optionRecipeRequiresOwnerGuardAndQueriesNothingWhenDenied() {
        when(guard.requireOwner(owner, outletId)).thenThrow(new ResourceNotFoundException());

        assertThrows(ResourceNotFoundException.class,
                () -> service.optionRecipe(owner, outletId, productId, groupId, optionId));

        verify(guard).requireOwner(owner, outletId);
        verifyNoInteractions(catalog, inventory, audit);
    }

    @Test
    void optionRecipeRejectsGroupIdThatDoesNotMatchTheOptionGroup() {
        UUID otherGroupId = UUID.randomUUID();
        OptionGroupEntity otherGroup = OptionGroupEntity.create(tenant, outlet, product,
                new OptionGroupEntity.Details("Size", false, 0, 1, 1));
        ReflectionTestUtils.setField(otherGroup, "id", otherGroupId);
        stubOwner();
        stubProduct();
        when(catalog.group(tenantId, outletId, otherGroupId)).thenReturn(Optional.of(otherGroup));
        when(catalog.option(tenantId, outletId, optionId)).thenReturn(Optional.of(option));

        assertThrows(ResourceNotFoundException.class,
                () -> service.optionRecipe(owner, outletId, productId, otherGroupId, optionId));

        verifyNoInteractions(inventory);
    }

    @Test
    void optionRecipeRejectsMissingOption() {
        stubOwner();
        stubProduct();
        stubGroup();
        when(catalog.option(tenantId, outletId, optionId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.optionRecipe(owner, outletId, productId, groupId, optionId));

        verifyNoInteractions(inventory);
    }

    @Test
    void optionRecipeReturnsMappedLinesFromOptionRecipesQuery() {
        stubOwner();
        stubOption();
        when(inventory.optionRecipes(tenantId, outletId, List.of(optionId))).thenReturn(List.of(
                RecipeLineEntity.forOption(tenant, outlet, option, itemA, new BigDecimal("50.000"))));

        FnbRecipeResponse response = service.optionRecipe(owner, outletId, productId, groupId, optionId);

        assertEquals(1, response.getLines().size());
        FnbRecipeResponse.Line line = response.getLines().get(0);
        assertEquals(itemAId, line.getInventoryItemId());
        assertEquals("Kopi", line.getInventoryItemName());
        assertEquals("g", line.getUnit());
        assertEquals(0, new BigDecimal("50").compareTo(line.getQuantityPerUnit()));
        verify(inventory, never()).deleteOptionRecipe(any(UUID.class), any(UUID.class), any(UUID.class));
        verify(inventory, never()).persist(any(RecipeLineEntity.class));
        verifyNoInteractions(audit);
    }

    @Test
    void optionRecipeReturnsEmptyLinesWhenOptionHasNoRecipe() {
        stubOwner();
        stubOption();
        when(inventory.optionRecipes(tenantId, outletId, List.of(optionId))).thenReturn(List.of());

        FnbRecipeResponse response = service.optionRecipe(owner, outletId, productId, groupId, optionId);

        assertTrue(response.getLines().isEmpty());
    }

    // ---------------------------------------------------------------- regression: updateOption / recordPrice

    @Test
    void updateOptionRequiresOwnerGuard() {
        when(guard.requireOwner(owner, outletId)).thenThrow(new ResourceNotFoundException());

        assertThrows(ResourceNotFoundException.class,
                () -> service.updateOption(owner, outletId, productId, groupId, optionId, optionRequest("Boba", "3000.00", null)));

        verifyNoInteractions(catalog, audit, references);
    }

    @Test
    void updateOptionPriceChangeWithoutReasonIsRejectedAndNothingIsAudited() {
        stubOwner();
        stubOption();

        InvalidOperationException error = assertThrows(InvalidOperationException.class,
                () -> service.updateOption(owner, outletId, productId, groupId, optionId, optionRequest("Boba", "4000.00", null)));

        assertEquals(PRICE_REASON_MESSAGE, error.getMessage());
        verifyNoInteractions(audit);
        assertEquals(0, new BigDecimal("3000.00").compareTo(option.getPriceAdjustment()));
    }

    @Test
    void updateOptionPriceChangeWithBlankReasonIsRejected() {
        stubOwner();
        stubOption();

        InvalidOperationException error = assertThrows(InvalidOperationException.class,
                () -> service.updateOption(owner, outletId, productId, groupId, optionId, optionRequest("Boba", "4000.00", "   ")));

        assertEquals(PRICE_REASON_MESSAGE, error.getMessage());
        verifyNoInteractions(audit);
        assertEquals(0, new BigDecimal("3000.00").compareTo(option.getPriceAdjustment()));
    }

    @Test
    void updateOptionPriceChangeWithReasonRecordsOptionPriceAuditWithTrimmedReason() {
        stubOwner();
        stubOption();
        stubAuditReferences();

        service.updateOption(owner, outletId, productId, groupId, optionId,
                optionRequest("Boba", "4000.00", "  Harga bahan naik  "));

        AuditLogEntity log = captureAudit();
        assertEquals("FNB_OPTION_PRICE_CHANGED", log.getAction());
        assertEquals("PRODUCT_OPTION", log.getEntityType());
        assertEquals(optionId, log.getEntityId());
        assertEquals("Harga bahan naik", log.getReason());
        assertSame(tenant, log.getTenant());
        assertSame(outlet, log.getOutlet());
        assertSame(user, log.getActor());
    }

    @Test
    void updateOptionAuditDetailsUsePlainOldAndNewPrices() {
        stubOwner();
        stubOption();
        stubAuditReferences();

        // 4.5E+3 must be rendered with toPlainString(), and old value keeps its stored scale.
        service.updateOption(owner, outletId, productId, groupId, optionId,
                optionRequest("Boba", "4.5E+3", "Naik"));

        assertEquals("old=3000.00;new=4500", captureAudit().getDetails());
    }

    @Test
    void updateOptionPriceChangeAppliesNewValuesAfterAuditing() {
        stubOwner();
        stubOption();
        stubAuditReferences();

        FnbOptionResponse response = service.updateOption(owner, outletId, productId, groupId, optionId,
                optionRequest("Boba Premium", "4000.00", "Naik"));

        assertEquals("Boba Premium", option.getName());
        assertEquals(0, new BigDecimal("4000.00").compareTo(option.getPriceAdjustment()));
        assertEquals("Boba Premium", response.getName());
        assertEquals(0, new BigDecimal("4000.00").compareTo(response.getPriceAdjustment()));
        assertEquals(groupId, response.getOptionGroupId());
    }

    @Test
    void updateOptionWithUnchangedPriceWritesNoAuditEvenWhenScaleDiffers() {
        stubOwner();
        stubOption();

        FnbOptionResponse response = service.updateOption(owner, outletId, productId, groupId, optionId,
                optionRequest("Boba Renamed", "3000", "ignored reason"));

        verifyNoInteractions(audit, references);
        assertEquals("Boba Renamed", option.getName());
        assertEquals("Boba Renamed", response.getName());
    }

    @Test
    void updateOptionRejectsGroupIdThatDoesNotMatchTheOptionGroup() {
        UUID otherGroupId = UUID.randomUUID();
        OptionGroupEntity otherGroup = OptionGroupEntity.create(tenant, outlet, product,
                new OptionGroupEntity.Details("Size", false, 0, 1, 1));
        ReflectionTestUtils.setField(otherGroup, "id", otherGroupId);
        stubOwner();
        stubProduct();
        when(catalog.group(tenantId, outletId, otherGroupId)).thenReturn(Optional.of(otherGroup));
        when(catalog.option(tenantId, outletId, optionId)).thenReturn(Optional.of(option));

        assertThrows(ResourceNotFoundException.class,
                () -> service.updateOption(owner, outletId, productId, otherGroupId, optionId,
                        optionRequest("Hacked", "4000.00", "Naik")));

        verifyNoInteractions(audit);
        assertEquals("Boba", option.getName());
        assertEquals(0, new BigDecimal("3000.00").compareTo(option.getPriceAdjustment()));
    }

    // ---------------------------------------------------------------- regression: updateProduct / recordPrice

    @Test
    void updateProductRequiresOwnerGuard() {
        when(guard.requireOwner(owner, outletId)).thenThrow(new ResourceNotFoundException());

        assertThrows(ResourceNotFoundException.class,
                () -> service.updateProduct(owner, outletId, productId, productRequest("Latte", "10000.00", null)));

        verifyNoInteractions(catalog, audit, references);
    }

    @Test
    void updateProductPriceChangeWithoutReasonIsRejectedAndNothingIsAudited() {
        stubOwner();
        stubProduct();
        stubCategory();

        InvalidOperationException error = assertThrows(InvalidOperationException.class,
                () -> service.updateProduct(owner, outletId, productId, productRequest("Latte", "12000.00", null)));

        assertEquals(PRICE_REASON_MESSAGE, error.getMessage());
        verifyNoInteractions(audit);
        assertEquals(0, new BigDecimal("10000.00").compareTo(product.getPrice()));
    }

    @Test
    void updateProductPriceChangeWithBlankReasonIsRejected() {
        stubOwner();
        stubProduct();
        stubCategory();

        InvalidOperationException error = assertThrows(InvalidOperationException.class,
                () -> service.updateProduct(owner, outletId, productId, productRequest("Latte", "12000.00", "  ")));

        assertEquals(PRICE_REASON_MESSAGE, error.getMessage());
        verifyNoInteractions(audit);
    }

    @Test
    void updateProductPriceChangeWithReasonRecordsProductPriceAuditWithTrimmedReason() {
        stubOwner();
        stubProduct();
        stubCategory();
        stubAuditReferences();

        service.updateProduct(owner, outletId, productId, productRequest("Latte", "12000.00", "  Promo selesai "));

        AuditLogEntity log = captureAudit();
        assertEquals("FNB_PRODUCT_PRICE_CHANGED", log.getAction());
        assertEquals("PRODUCT", log.getEntityType());
        assertEquals(productId, log.getEntityId());
        assertEquals("Promo selesai", log.getReason());
        assertEquals("old=10000.00;new=12000.00", log.getDetails());
        assertSame(tenant, log.getTenant());
        assertSame(outlet, log.getOutlet());
        assertSame(user, log.getActor());
    }

    @Test
    void updateProductAuditDetailsUsePlainOldAndNewPrices() {
        stubOwner();
        stubProduct();
        stubCategory();
        stubAuditReferences();

        service.updateProduct(owner, outletId, productId, productRequest("Latte", "1.2E+4", "Naik"));

        assertEquals("old=10000.00;new=12000", captureAudit().getDetails());
    }

    @Test
    void updateProductPriceChangeAppliesNewValuesAfterAuditing() {
        stubOwner();
        stubProduct();
        stubCategory();
        stubAuditReferences();

        FnbProductResponse response = service.updateProduct(owner, outletId, productId,
                productRequest("Latte Baru", "12000.00", "Naik"));

        assertEquals("Latte Baru", product.getName());
        assertEquals(0, new BigDecimal("12000.00").compareTo(product.getPrice()));
        assertEquals("Latte Baru", response.getName());
        assertEquals(0, new BigDecimal("12000.00").compareTo(response.getPrice()));
        assertEquals(categoryId, response.getCategoryId());
        assertEquals("old=10000.00;new=12000.00", captureAudit().getDetails());
    }

    @Test
    void updateProductWithUnchangedPriceWritesNoAuditEvenWhenScaleDiffers() {
        stubOwner();
        stubProduct();
        stubCategory();

        FnbProductResponse response = service.updateProduct(owner, outletId, productId,
                productRequest("Latte Renamed", "10000", "ignored reason"));

        verifyNoInteractions(audit, references);
        assertEquals("Latte Renamed", product.getName());
        assertEquals("Latte Renamed", response.getName());
    }

    @Test
    void updateProductRejectsUnknownProductId() {
        stubOwner();
        when(catalog.product(tenantId, outletId, productId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.updateProduct(owner, outletId, productId, productRequest("Latte", "12000.00", "Naik")));

        verifyNoInteractions(audit);
    }

    @Test
    void updateProductRejectsUnknownCategoryWithoutAuditing() {
        stubOwner();
        stubProduct();
        when(catalog.category(tenantId, outletId, categoryId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.updateProduct(owner, outletId, productId, productRequest("Latte", "12000.00", "Naik")));

        verifyNoInteractions(audit);
        assertEquals(0, new BigDecimal("10000.00").compareTo(product.getPrice()));
    }

    // ---------------------------------------------------------------- helpers

    private void stubOwner() {
        when(guard.requireOwner(owner, outletId)).thenReturn(context);
    }

    private void stubProduct() {
        when(catalog.product(tenantId, outletId, productId)).thenReturn(Optional.of(product));
    }

    private void stubCategory() {
        when(catalog.category(tenantId, outletId, categoryId)).thenReturn(Optional.of(category));
    }

    private void stubGroup() {
        when(catalog.group(tenantId, outletId, groupId)).thenReturn(Optional.of(group));
    }

    private void stubOption() {
        stubProduct();
        stubGroup();
        when(catalog.option(tenantId, outletId, optionId)).thenReturn(Optional.of(option));
    }

    private void stubItem(InventoryItemEntity item) {
        when(inventory.item(tenantId, outletId, item.getId())).thenReturn(Optional.of(item));
    }

    private void stubAuditReferences() {
        when(references.tenant(tenantId)).thenReturn(tenant);
        when(references.outlet(outletId)).thenReturn(outlet);
        when(references.user(userId)).thenReturn(user);
    }

    private void arrangeProductRecipeHappyPath() {
        stubOwner();
        stubProduct();
        stubItem(itemA);
        stubItem(itemB);
        stubAuditReferences();
    }

    private void arrangeOptionRecipeHappyPath() {
        stubOwner();
        stubOption();
        stubItem(itemA);
        stubItem(itemB);
        stubAuditReferences();
    }

    private void verifyNothingWrittenForRecipe() {
        verify(inventory, never()).deleteProductRecipe(any(UUID.class), any(UUID.class), any(UUID.class));
        verify(inventory, never()).deleteOptionRecipe(any(UUID.class), any(UUID.class), any(UUID.class));
        verify(inventory, never()).persist(any(RecipeLineEntity.class));
        verify(audit, never()).persist(any(AuditLogEntity.class));
    }

    private AuditLogEntity captureAudit() {
        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(audit).persist(captor.capture());
        return captor.getValue();
    }

    private InventoryItemEntity inventoryItem(UUID id, String name, String unit) {
        InventoryItemEntity item = InventoryItemEntity.create(tenant, outlet, null, name, unit, BigDecimal.ZERO);
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    private ProductEntity.Details productDetails(CategoryEntity details, String price) {
        return new ProductEntity.Details(details, "Latte", null, new BigDecimal(price), null, true, 0);
    }

    private FnbRecipeRequest.Line line(UUID itemId, String quantity) {
        FnbRecipeRequest.Line line = new FnbRecipeRequest.Line();
        line.setInventoryItemId(itemId);
        line.setQuantityPerUnit(new BigDecimal(quantity));
        return line;
    }

    private FnbRecipeRequest recipeRequest(FnbRecipeRequest.Line... lines) {
        FnbRecipeRequest request = new FnbRecipeRequest();
        request.setLines(new ArrayList<>(Arrays.asList(lines)));
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

    private FnbProductRequest productRequest(String name, String price, String reason) {
        FnbProductRequest request = new FnbProductRequest();
        request.setCategoryId(categoryId);
        request.setName(name);
        request.setPrice(new BigDecimal(price));
        request.setAvailable(true);
        request.setDisplayOrder(0);
        request.setPriceChangeReason(reason);
        return request;
    }
}
