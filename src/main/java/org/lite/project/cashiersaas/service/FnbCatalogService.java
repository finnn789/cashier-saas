package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.FnbCatalogMapper;
import org.lite.project.cashiersaas.dto.request.*;
import org.lite.project.cashiersaas.dto.response.*;
import org.lite.project.cashiersaas.entity.fnb.*;
import org.lite.project.cashiersaas.entity.shared.catalog.CategoryEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.OptionGroupEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductOptionEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductOptionGroupEntity;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbCatalogRepository;
import org.lite.project.cashiersaas.repository.FnbInventoryRepository;
import org.lite.project.cashiersaas.repository.MerchantReferenceRepository;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryItemEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.RecipeLineEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

@Service
@RequiredArgsConstructor
public class FnbCatalogService {
    private final MerchantAccessGuard guard;
    private final FnbCatalogRepository catalog;
    private final MerchantReferenceRepository references;
    private final AuditLogRepository audit;
    private final FnbInventoryRepository inventory;

    @Transactional
    public FnbCategoryResponse createCategory(TenantPrincipal principal, UUID outletId, FnbCategoryRequest request) {
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        CategoryEntity entity = CategoryEntity.create(context.getTenant(), context.getOutlet(), request.getName().trim(), request.getDisplayOrder());
        catalog.persist(entity);
        return FnbCatalogMapper.category(entity);
    }
    @Transactional
    public FnbCategoryResponse updateCategory(TenantPrincipal principal, UUID outletId, UUID id, FnbCategoryRequest request) {
        guard.requireOwner(principal, outletId);
        CategoryEntity entity = category(principal.getTenantId(), outletId, id);
        entity.update(request.getName().trim(), request.getDisplayOrder());
        return FnbCatalogMapper.category(entity);
    }
    @Transactional
    public FnbCategoryResponse archiveCategory(TenantPrincipal principal, UUID outletId, UUID id) {
        guard.requireOwner(principal, outletId);
        CategoryEntity entity = category(principal.getTenantId(), outletId, id);
        entity.archive();
        return FnbCatalogMapper.category(entity);
    }
    @Transactional(readOnly = true)
    public List<FnbCategoryResponse> categories(TenantPrincipal principal, UUID outletId) {
        guard.requireOutlet(principal, outletId);
        return catalog.categories(principal.getTenantId(), outletId).stream().map(FnbCatalogMapper::category).toList();
    }
    @Transactional
    public FnbProductResponse createProduct(TenantPrincipal principal, UUID outletId, FnbProductRequest request) {
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        CategoryEntity category = category(principal.getTenantId(), outletId, request.getCategoryId());
        ProductEntity entity = ProductEntity.create(context.getTenant(), context.getOutlet(),
                productDetails(category, request));
        catalog.persist(entity);
        return FnbCatalogMapper.product(entity);
    }
    @Transactional
    public FnbProductResponse updateProduct(TenantPrincipal principal, UUID outletId, UUID id, FnbProductRequest request) {
        guard.requireOwner(principal, outletId);
        ProductEntity entity = product(principal.getTenantId(), outletId, id);
        CategoryEntity category = category(principal.getTenantId(), outletId, request.getCategoryId());
        if (entity.getPrice().compareTo(request.getPrice()) != 0) {
            requirePriceReason(request.getPriceChangeReason());
            recordPrice(principal, outletId, id, AuditLogEntity.Action.FNB_PRODUCT_PRICE_CHANGED,
                    request.getPriceChangeReason(), entity.getPrice(), request.getPrice());
        }
        entity.update(productDetails(category, request));
        return FnbCatalogMapper.product(entity);
    }
    @Transactional
    public FnbProductResponse archiveProduct(TenantPrincipal principal, UUID outletId, UUID id) {
        guard.requireOwner(principal, outletId);
        ProductEntity entity = product(principal.getTenantId(), outletId, id);
        entity.archive();
        return FnbCatalogMapper.product(entity);
    }
    @Transactional(readOnly = true)
    public List<FnbProductResponse> products(TenantPrincipal principal, UUID outletId) {
        guard.requireOutlet(principal, outletId);
        return catalog.products(principal.getTenantId(), outletId).stream()
                .filter(p -> !p.getCategory().isArchived()).map(FnbCatalogMapper::product).toList();
    }
    @Transactional
    public FnbOptionGroupResponse createGroup(TenantPrincipal principal, UUID outletId, FnbOptionGroupRequest request) {
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        validateGroup(request);
        OptionGroupEntity entity = OptionGroupEntity.create(context.getTenant(), context.getOutlet(), groupDetails(request));
        catalog.persist(entity);
        return FnbCatalogMapper.group(entity);
    }
    @Transactional
    public FnbOptionGroupResponse updateGroup(TenantPrincipal principal, UUID outletId, UUID id, FnbOptionGroupRequest request) {
        guard.requireOwner(principal, outletId);
        validateGroup(request);
        OptionGroupEntity entity = group(principal.getTenantId(), outletId, id);
        entity.update(groupDetails(request));
        return FnbCatalogMapper.group(entity);
    }
    @Transactional
    public FnbOptionGroupResponse archiveGroup(TenantPrincipal principal, UUID outletId, UUID id) {
        guard.requireOwner(principal, outletId);
        OptionGroupEntity entity = group(principal.getTenantId(), outletId, id);
        if (catalog.groupInUse(principal.getTenantId(), outletId, id)) {
            throw new OperationConflictException("Grup pilihan masih dipakai produk.");
        }
        entity.archive();
        return FnbCatalogMapper.group(entity);
    }
    @Transactional(readOnly = true)
    public List<FnbOptionGroupResponse> groups(TenantPrincipal principal, UUID outletId) {
        guard.requireOutlet(principal, outletId);
        return catalog.outletGroups(principal.getTenantId(), outletId).stream().map(FnbCatalogMapper::group).toList();
    }
    @Transactional(readOnly = true)
    public List<FnbOptionGroupResponse> productGroups(TenantPrincipal principal, UUID outletId, UUID productId) {
        guard.requireOutlet(principal, outletId);
        product(principal.getTenantId(), outletId, productId);
        return catalog.groups(principal.getTenantId(), outletId, productId).stream().map(FnbCatalogMapper::group).toList();
    }
    @Transactional
    public List<FnbOptionGroupResponse> replaceProductGroups(TenantPrincipal principal, UUID outletId, UUID productId,
                                                             FnbProductOptionGroupsRequest request) {
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        ProductEntity product = product(principal.getTenantId(), outletId, productId);
        Set<UUID> seen = new HashSet<>();
        List<OptionGroupEntity> groups = new ArrayList<>();
        for (UUID groupId : request.getGroupIds()) {
            if (groupId == null || !seen.add(groupId)) throw new InvalidOperationException("Grup pilihan tidak boleh duplikat.");
            groups.add(group(principal.getTenantId(), outletId, groupId));
        }
        catalog.deleteProductGroups(principal.getTenantId(), outletId, productId);
        for (int i = 0; i < groups.size(); i++) {
            catalog.persist(ProductOptionGroupEntity.create(context.getTenant(), context.getOutlet(), product, groups.get(i), i));
        }
        return groups.stream().map(FnbCatalogMapper::group).toList();
    }
    @Transactional
    public FnbOptionResponse createOption(TenantPrincipal principal, UUID outletId, UUID groupId, FnbOptionRequest request) {
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        OptionGroupEntity group = group(principal.getTenantId(), outletId, groupId);
        ProductOptionEntity entity = ProductOptionEntity.create(context.getTenant(), context.getOutlet(), group,
                request.getName().trim(), request.getPriceAdjustment(), request.isAvailable(), request.getDisplayOrder());
        catalog.persist(entity);
        return FnbCatalogMapper.option(entity);
    }
    @Transactional
    public FnbOptionResponse updateOption(TenantPrincipal principal, UUID outletId, UUID groupId, UUID id, FnbOptionRequest request) {
        guard.requireOwner(principal, outletId);
        ProductOptionEntity entity = option(principal.getTenantId(), outletId, groupId, id);
        if (entity.getPriceAdjustment().compareTo(request.getPriceAdjustment()) != 0) {
            requirePriceReason(request.getPriceChangeReason());
            recordPrice(principal, outletId, id, AuditLogEntity.Action.FNB_OPTION_PRICE_CHANGED,
                    request.getPriceChangeReason(), entity.getPriceAdjustment(), request.getPriceAdjustment());
        }
        entity.update(request.getName().trim(), request.getPriceAdjustment(), request.isAvailable(), request.getDisplayOrder());
        return FnbCatalogMapper.option(entity);
    }
    @Transactional(readOnly = true)
    public List<FnbOptionResponse> options(TenantPrincipal principal, UUID outletId, UUID groupId) {
        guard.requireOutlet(principal, outletId);
        group(principal.getTenantId(), outletId, groupId);
        return catalog.options(principal.getTenantId(), outletId, groupId).stream().map(FnbCatalogMapper::option).toList();
    }
    @Transactional
    public FnbRecipeResponse replaceProductRecipe(TenantPrincipal principal, UUID outletId, UUID productId, FnbRecipeRequest request) {
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        ProductEntity product = product(principal.getTenantId(), outletId, productId);
        return FnbCatalogMapper.recipe(replaceRecipe(principal, outletId, productId, request,
                () -> inventory.deleteProductRecipe(principal.getTenantId(), outletId, productId),
                (item, quantity) -> RecipeLineEntity.forProduct(context.getTenant(), context.getOutlet(), product, item, quantity)));
    }
    @Transactional(readOnly = true)
    public FnbRecipeResponse productRecipe(TenantPrincipal principal, UUID outletId, UUID productId) {
        guard.requireOwner(principal, outletId);
        product(principal.getTenantId(), outletId, productId);
        return FnbCatalogMapper.recipe(inventory.productRecipes(principal.getTenantId(), outletId, List.of(productId)));
    }
    @Transactional
    public FnbRecipeResponse replaceOptionRecipe(TenantPrincipal principal, UUID outletId, UUID groupId, UUID id, FnbRecipeRequest request) {
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        ProductOptionEntity option = option(principal.getTenantId(), outletId, groupId, id);
        return FnbCatalogMapper.recipe(replaceRecipe(principal, outletId, id, request,
                () -> inventory.deleteOptionRecipe(principal.getTenantId(), outletId, id),
                (item, quantity) -> RecipeLineEntity.forOption(context.getTenant(), context.getOutlet(), option, item, quantity)));
    }
    @Transactional(readOnly = true)
    public FnbRecipeResponse optionRecipe(TenantPrincipal principal, UUID outletId, UUID groupId, UUID id) {
        guard.requireOwner(principal, outletId);
        option(principal.getTenantId(), outletId, groupId, id);
        return FnbCatalogMapper.recipe(inventory.optionRecipes(principal.getTenantId(), outletId, List.of(id)));
    }
    private List<RecipeLineEntity> replaceRecipe(TenantPrincipal principal, UUID outletId, UUID ownerId, FnbRecipeRequest request,
                                                 Runnable clear, BiFunction<InventoryItemEntity, BigDecimal, RecipeLineEntity> factory) {
        Set<UUID> seen = new HashSet<>();
        List<InventoryItemEntity> items = new ArrayList<>();
        for (FnbRecipeRequest.Line line : request.getLines()) {
            if (!seen.add(line.getInventoryItemId())) throw new InvalidOperationException("Bahan resep tidak boleh duplikat.");
            items.add(inventory.item(principal.getTenantId(), outletId, line.getInventoryItemId()).orElseThrow(ResourceNotFoundException::new));
        }
        clear.run();
        List<RecipeLineEntity> lines = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            RecipeLineEntity line = factory.apply(items.get(i), request.getLines().get(i).getQuantityPerUnit());
            inventory.persist(line);
            lines.add(line);
        }
        recordAudit(principal, outletId, ownerId, AuditLogEntity.Action.FNB_RECIPE_CHANGED, null, "lines=" + lines.size());
        return lines;
    }
    private ProductOptionEntity option(UUID tenant, UUID outlet, UUID groupId, UUID id) {
        group(tenant, outlet, groupId);
        ProductOptionEntity entity = catalog.option(tenant, outlet, id).orElseThrow(ResourceNotFoundException::new);
        if (!entity.getOptionGroup().getId().equals(groupId)) throw new ResourceNotFoundException();
        return entity;
    }
    private CategoryEntity category(UUID tenant, UUID outlet, UUID id) {
        return catalog.category(tenant, outlet, id).orElseThrow(ResourceNotFoundException::new);
    }
    private ProductEntity product(UUID tenant, UUID outlet, UUID id) {
        ProductEntity entity = catalog.product(tenant, outlet, id).orElseThrow(ResourceNotFoundException::new);
        if (entity.getCategory().isArchived()) throw new ResourceNotFoundException();
        return entity;
    }
    private OptionGroupEntity group(UUID tenant, UUID outlet, UUID id) {
        return catalog.group(tenant, outlet, id).orElseThrow(ResourceNotFoundException::new);
    }
    private void validateGroup(FnbOptionGroupRequest request) {
        if (request.getMinimumSelections() > request.getMaximumSelections()
                || (request.isRequired() && request.getMinimumSelections() == 0)
                || (!request.isRequired() && request.getMinimumSelections() > 0)) {
            throw new InvalidOperationException("Batas pilihan grup tidak valid.");
        }
    }
    private ProductEntity.Details productDetails(CategoryEntity category, FnbProductRequest request) {
        return new ProductEntity.Details(category, request.getName().trim(), request.getDescription(),
                request.getPrice(), request.getImageUrl(), request.isAvailable(), request.getDisplayOrder());
    }
    private OptionGroupEntity.Details groupDetails(FnbOptionGroupRequest request) {
        return new OptionGroupEntity.Details(request.getName().trim(), request.isRequired(),
                request.getMinimumSelections(), request.getMaximumSelections(), request.getDisplayOrder());
    }
    private void requirePriceReason(String reason) {
        if (reason == null || reason.isBlank()) throw new InvalidOperationException("Alasan perubahan harga wajib diisi.");
    }
    private void recordPrice(TenantPrincipal principal, UUID outletId, UUID id, AuditLogEntity.Action action,
                             String reason, BigDecimal oldValue, BigDecimal newValue) {
        recordAudit(principal, outletId, id, action, reason.trim(),
                "old=" + oldValue.toPlainString() + ";new=" + newValue.toPlainString());
    }
    private void recordAudit(TenantPrincipal principal, UUID outletId, UUID id, AuditLogEntity.Action action,
                             String reason, String details) {
        audit.persist(AuditLogEntity.recordAction(references.tenant(principal.getTenantId()), references.outlet(outletId),
                references.user(principal.getUserId()), action, id, reason, details));
    }
}
