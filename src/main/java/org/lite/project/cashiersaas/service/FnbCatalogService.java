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
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbCatalogRepository;
import org.lite.project.cashiersaas.repository.MerchantReferenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FnbCatalogService {
    private final MerchantAccessGuard guard;
    private final FnbCatalogRepository catalog;
    private final MerchantReferenceRepository references;
    private final AuditLogRepository audit;

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
    public FnbOptionGroupResponse createGroup(TenantPrincipal principal, UUID outletId, UUID productId, FnbOptionGroupRequest request) {
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        validateGroup(request);
        ProductEntity product = product(principal.getTenantId(), outletId, productId);
        OptionGroupEntity entity = OptionGroupEntity.create(context.getTenant(), context.getOutlet(), product,
                groupDetails(request));
        catalog.persist(entity);
        return FnbCatalogMapper.group(entity);
    }
    @Transactional
    public FnbOptionGroupResponse updateGroup(TenantPrincipal principal, UUID outletId, UUID productId, UUID id, FnbOptionGroupRequest request) {
        guard.requireOwner(principal, outletId);
        validateGroup(request);
        product(principal.getTenantId(), outletId, productId);
        OptionGroupEntity entity = group(principal.getTenantId(), outletId, productId, id);
        entity.update(groupDetails(request));
        return FnbCatalogMapper.group(entity);
    }
    @Transactional(readOnly = true)
    public List<FnbOptionGroupResponse> groups(TenantPrincipal principal, UUID outletId, UUID productId) {
        guard.requireOutlet(principal, outletId);
        product(principal.getTenantId(), outletId, productId);
        return catalog.groups(principal.getTenantId(), outletId, productId).stream().map(FnbCatalogMapper::group).toList();
    }
    @Transactional
    public FnbOptionResponse createOption(TenantPrincipal principal, UUID outletId, UUID productId, UUID groupId, FnbOptionRequest request) {
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        OptionGroupEntity group = group(principal.getTenantId(), outletId, productId, groupId);
        ProductOptionEntity entity = ProductOptionEntity.create(context.getTenant(), context.getOutlet(), group,
                request.getName().trim(), request.getPriceAdjustment(), request.isAvailable(), request.getDisplayOrder());
        catalog.persist(entity);
        return FnbCatalogMapper.option(entity);
    }
    @Transactional
    public FnbOptionResponse updateOption(TenantPrincipal principal, UUID outletId, UUID productId, UUID groupId, UUID id, FnbOptionRequest request) {
        guard.requireOwner(principal, outletId);
        group(principal.getTenantId(), outletId, productId, groupId);
        ProductOptionEntity entity = catalog.option(principal.getTenantId(), outletId, id).orElseThrow(ResourceNotFoundException::new);
        if (!entity.getOptionGroup().getId().equals(groupId)) throw new ResourceNotFoundException();
        if (entity.getPriceAdjustment().compareTo(request.getPriceAdjustment()) != 0) {
            requirePriceReason(request.getPriceChangeReason());
            recordPrice(principal, outletId, id, AuditLogEntity.Action.FNB_OPTION_PRICE_CHANGED,
                    request.getPriceChangeReason(), entity.getPriceAdjustment(), request.getPriceAdjustment());
        }
        entity.update(request.getName().trim(), request.getPriceAdjustment(), request.isAvailable(), request.getDisplayOrder());
        return FnbCatalogMapper.option(entity);
    }
    @Transactional(readOnly = true)
    public List<FnbOptionResponse> options(TenantPrincipal principal, UUID outletId, UUID productId, UUID groupId) {
        guard.requireOutlet(principal, outletId);
        group(principal.getTenantId(), outletId, productId, groupId);
        return catalog.options(principal.getTenantId(), outletId, groupId).stream().map(FnbCatalogMapper::option).toList();
    }
    private CategoryEntity category(UUID tenant, UUID outlet, UUID id) {
        return catalog.category(tenant, outlet, id).orElseThrow(ResourceNotFoundException::new);
    }
    private ProductEntity product(UUID tenant, UUID outlet, UUID id) {
        ProductEntity entity = catalog.product(tenant, outlet, id).orElseThrow(ResourceNotFoundException::new);
        if (entity.getCategory().isArchived()) throw new ResourceNotFoundException();
        return entity;
    }
    private OptionGroupEntity group(UUID tenant, UUID outlet, UUID productId, UUID id) {
        product(tenant, outlet, productId);
        OptionGroupEntity entity = catalog.group(tenant, outlet, id).orElseThrow(ResourceNotFoundException::new);
        if (!entity.getProduct().getId().equals(productId)) throw new ResourceNotFoundException();
        return entity;
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
        audit.persist(AuditLogEntity.recordAction(references.tenant(principal.getTenantId()), references.outlet(outletId),
                references.user(principal.getUserId()), action, id, reason.trim(),
                "old=" + oldValue.toPlainString() + ";new=" + newValue.toPlainString()));
    }
}
