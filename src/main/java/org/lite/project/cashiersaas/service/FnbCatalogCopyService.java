package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
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
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryBalanceEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryItemEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.RecipeLineEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbCatalogRepository;
import org.lite.project.cashiersaas.repository.FnbInventoryRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.repository.OutletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Owner setup: copies the live menu, option groups, inventory item masters and recipes of one outlet into an empty outlet. */
@Service
@RequiredArgsConstructor
public class FnbCatalogCopyService {
    private final MerchantAccessGuard guard;
    private final OutletRepository outlets;
    private final FnbOrderRepository orders;
    private final FnbCatalogRepository catalog;
    private final FnbInventoryRepository inventory;
    private final AuditLogRepository audit;

    @Transactional
    public FnbCatalogCopyResponse copy(TenantPrincipal principal, UUID targetOutletId, FnbCatalogCopyRequest request) {
        MerchantAccessContext context = guard.requireOwnerIgnoringAllowance(principal, principal.getOutletId());
        UUID tenantId = principal.getTenantId();
        OutletEntity target = outlets.findInTenant(tenantId, targetOutletId).orElseThrow(ResourceNotFoundException::new);
        OutletEntity source = outlets.findInTenant(tenantId, request.getSourceOutletId()).orElseThrow(ResourceNotFoundException::new);
        if (source.getId().equals(target.getId())) throw new InvalidOperationException("Outlet sumber dan tujuan harus berbeda.");
        if (!target.isActive()) throw new OperationConflictException("Outlet tujuan tidak aktif.");
        // Ascending UUID order (string compare = unsigned; UUID.compareTo is signed) so A->B and B->A copies cannot deadlock.
        boolean targetFirst = target.getId().toString().compareTo(source.getId().toString()) < 0;
        orders.lockOutlet(tenantId, targetFirst ? target.getId() : source.getId());
        orders.lockOutlet(tenantId, targetFirst ? source.getId() : target.getId());
        if (catalog.hasProductsOrGroups(tenantId, target.getId()) || inventory.hasItems(tenantId, target.getId())) {
            throw new OperationConflictException("Outlet tujuan sudah memiliki menu atau bahan.");
        }
        TenantEntity tenant = context.getTenant();
        FnbCatalogCopyResponse result = new FnbCatalogCopyResponse();
        Map<UUID, CategoryEntity> categories = copyCategories(tenant, target, source, result);
        Map<UUID, ProductEntity> products = copyProducts(tenant, target, source, categories);
        Map<UUID, OptionGroupEntity> groups = copyGroups(tenant, target, source);
        Map<UUID, ProductOptionEntity> options = copyOptions(tenant, target, source, groups);
        copyLinks(tenant, target, source, products, groups);
        Map<UUID, InventoryItemEntity> items = copyItems(tenant, target, source);
        copyRecipes(tenant, target, source, products, options, items, result);
        result.setProducts(products.size());
        result.setOptionGroups(groups.size());
        result.setOptions(options.size());
        result.setInventoryItems(items.size());
        audit.persist(AuditLogEntity.recordAction(tenant, target, context.getMembership().getUser(),
                AuditLogEntity.Action.CATALOG_COPIED, target.getId(), null,
                "source=" + source.getId() + " categories=" + (result.getCategoriesCreated() + result.getCategoriesReused())
                        + " products=" + result.getProducts() + " optionGroups=" + result.getOptionGroups()
                        + " options=" + result.getOptions() + " inventoryItems=" + result.getInventoryItems()
                        + " recipeLines=" + result.getRecipeLines()));
        return result;
    }

    /** Source category id to the target category; target names match case-insensitively and are reused. */
    private Map<UUID, CategoryEntity> copyCategories(TenantEntity tenant, OutletEntity target, OutletEntity source,
                                                     FnbCatalogCopyResponse result) {
        Map<String, CategoryEntity> byName = new HashMap<>();
        for (CategoryEntity existing : catalog.allCategories(tenant.getId(), target.getId())) {
            byName.put(key(existing.getName()), existing);
        }
        Map<UUID, CategoryEntity> mapped = new HashMap<>();
        for (CategoryEntity from : catalog.categories(tenant.getId(), source.getId())) {
            CategoryEntity to = byName.get(key(from.getName()));
            if (to == null) {
                to = CategoryEntity.create(tenant, target, from.getName().trim(), from.getDisplayOrder());
                catalog.persist(to);
                byName.put(key(from.getName()), to);
                result.setCategoriesCreated(result.getCategoriesCreated() + 1);
            } else if (to.isArchived()) {
                throw new OperationConflictException("Outlet tujuan memiliki kategori terarsip dengan nama yang sama.");
            } else {
                result.setCategoriesReused(result.getCategoriesReused() + 1);
            }
            mapped.put(from.getId(), to);
        }
        return mapped;
    }

    private Map<UUID, ProductEntity> copyProducts(TenantEntity tenant, OutletEntity target, OutletEntity source,
                                                  Map<UUID, CategoryEntity> categories) {
        Map<UUID, ProductEntity> mapped = new HashMap<>();
        for (ProductEntity from : catalog.products(tenant.getId(), source.getId())) {
            CategoryEntity category = categories.get(from.getCategory().getId());
            if (category == null) continue; // archived source category: the product is hidden there too
            ProductEntity to = ProductEntity.create(tenant, target, new ProductEntity.Details(category, from.getName(),
                    from.getDescription(), from.getPrice(), from.getImageUrl(), from.isAvailable(), from.getDisplayOrder()));
            catalog.persist(to);
            mapped.put(from.getId(), to);
        }
        return mapped;
    }

    private Map<UUID, OptionGroupEntity> copyGroups(TenantEntity tenant, OutletEntity target, OutletEntity source) {
        Map<UUID, OptionGroupEntity> mapped = new HashMap<>();
        for (OptionGroupEntity from : catalog.outletGroups(tenant.getId(), source.getId())) {
            OptionGroupEntity to = OptionGroupEntity.create(tenant, target, new OptionGroupEntity.Details(from.getName(),
                    from.isRequired(), from.getMinimumSelections(), from.getMaximumSelections(), from.getDisplayOrder()));
            catalog.persist(to);
            mapped.put(from.getId(), to);
        }
        return mapped;
    }

    private Map<UUID, ProductOptionEntity> copyOptions(TenantEntity tenant, OutletEntity target, OutletEntity source,
                                                       Map<UUID, OptionGroupEntity> groups) {
        Map<UUID, ProductOptionEntity> mapped = new HashMap<>();
        for (ProductOptionEntity from : catalog.optionsOfGroups(tenant.getId(), source.getId(), List.copyOf(groups.keySet()))) {
            ProductOptionEntity to = ProductOptionEntity.create(tenant, target, groups.get(from.getOptionGroup().getId()),
                    from.getName(), from.getPriceAdjustment(), from.isAvailable(), from.getDisplayOrder());
            catalog.persist(to);
            mapped.put(from.getId(), to);
        }
        return mapped;
    }

    private void copyLinks(TenantEntity tenant, OutletEntity target, OutletEntity source,
                           Map<UUID, ProductEntity> products, Map<UUID, OptionGroupEntity> groups) {
        for (ProductOptionGroupEntity from : catalog.productGroupLinks(tenant.getId(), source.getId())) {
            ProductEntity product = products.get(from.getProduct().getId());
            OptionGroupEntity group = groups.get(from.getOptionGroup().getId());
            if (product != null && group != null) {
                catalog.persist(ProductOptionGroupEntity.create(tenant, target, product, group, from.getDisplayOrder()));
            }
        }
    }

    /** Active items only, with a zero balance and no cost or movements. */
    private Map<UUID, InventoryItemEntity> copyItems(TenantEntity tenant, OutletEntity target, OutletEntity source) {
        Map<UUID, InventoryItemEntity> mapped = new HashMap<>();
        for (InventoryItemEntity from : inventory.items(tenant.getId(), source.getId())) {
            InventoryItemEntity to = InventoryItemEntity.create(tenant, target, from.getSku(), from.getName(), from.getUnit(),
                    from.getReorderLevel());
            inventory.persist(to);
            mapped.put(from.getId(), to);
        }
        inventory.flush();
        for (InventoryItemEntity to : mapped.values()) {
            inventory.persist(InventoryBalanceEntity.create(tenant.getId(), target.getId(), to));
        }
        return mapped;
    }

    private void copyRecipes(TenantEntity tenant, OutletEntity target, OutletEntity source, Map<UUID, ProductEntity> products,
                             Map<UUID, ProductOptionEntity> options, Map<UUID, InventoryItemEntity> items,
                             FnbCatalogCopyResponse result) {
        for (RecipeLineEntity from : inventory.productRecipes(tenant.getId(), source.getId(), List.copyOf(products.keySet()))) {
            InventoryItemEntity item = items.get(from.getInventoryItem().getId());
            if (item == null) {
                result.setRecipeLinesSkipped(result.getRecipeLinesSkipped() + 1);
                continue;
            }
            inventory.persist(RecipeLineEntity.forProduct(tenant, target, products.get(from.getProduct().getId()), item,
                    from.getQuantityPerUnit()));
            result.setRecipeLines(result.getRecipeLines() + 1);
        }
        for (RecipeLineEntity from : inventory.optionRecipes(tenant.getId(), source.getId(), List.copyOf(options.keySet()))) {
            InventoryItemEntity item = items.get(from.getInventoryItem().getId());
            if (item == null) {
                result.setRecipeLinesSkipped(result.getRecipeLinesSkipped() + 1);
                continue;
            }
            inventory.persist(RecipeLineEntity.forOption(tenant, target, options.get(from.getProductOption().getId()), item,
                    from.getQuantityPerUnit()));
            result.setRecipeLines(result.getRecipeLines() + 1);
        }
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
