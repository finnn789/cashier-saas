package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.FnbMenuMapper;
import org.lite.project.cashiersaas.dto.request.FnbTableRequest;
import org.lite.project.cashiersaas.dto.response.FnbTableResponse;
import org.lite.project.cashiersaas.dto.response.PublicMenuResponse;
import org.lite.project.cashiersaas.entity.fnb.*;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.FnbCatalogRepository;
import org.lite.project.cashiersaas.repository.FnbTableRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FnbTableService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final MerchantAccessGuard guard;
    private final FnbTableRepository tables;
    private final FnbCatalogRepository catalog;
    private final FnbOrderRepository orders;
    private final PublicQrAccessService publicQrAccess;

    @Transactional
    public FnbTableResponse create(TenantPrincipal principal, UUID outletId, FnbTableRequest request) {
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        DiningTableEntity table = DiningTableEntity.create(context.getTenant(), context.getOutlet(), request.getName().trim());
        table.setActive(request.isActive());
        tables.persist(table);
        TableQrTokenEntity qr = TableQrTokenEntity.create(context.getTenant(), context.getOutlet(), table, randomToken());
        tables.persist(qr);
        return FnbMenuMapper.table(table, qr);
    }
    @Transactional
    public FnbTableResponse update(TenantPrincipal principal, UUID outletId, UUID id, FnbTableRequest request) {
        guard.requireOwner(principal, outletId);
        orders.lockTenant(principal.getTenantId());
        DiningTableEntity table = table(principal.getTenantId(), outletId, id);
        table.rename(request.getName().trim()); table.setActive(request.isActive());
        return FnbMenuMapper.table(table, qr(principal.getTenantId(), outletId, id));
    }
    @Transactional
    public FnbTableResponse rotate(TenantPrincipal principal, UUID outletId, UUID id) {
        guard.requireOwner(principal, outletId);
        orders.lockTenant(principal.getTenantId());
        DiningTableEntity table = table(principal.getTenantId(), outletId, id);
        TableQrTokenEntity qr = qr(principal.getTenantId(), outletId, id);
        qr.rotate(randomToken());
        return FnbMenuMapper.table(table, qr);
    }
    @Transactional(readOnly = true)
    public List<FnbTableResponse> list(TenantPrincipal principal, UUID outletId) {
        guard.requireOutlet(principal, outletId);
        return tables.list(principal.getTenantId(), outletId).stream()
                .map(table -> FnbMenuMapper.table(table, qr(principal.getTenantId(), outletId, table.getId()))).toList();
    }
    @Transactional(readOnly = true)
    public PublicMenuResponse publicMenu(String token) {
        TableQrTokenEntity qr = publicQrAccess.requirePublicQr(token);
        UUID tenantId = qr.getTenant().getId();
        UUID outletId = qr.getOutlet().getId();
        List<CategoryEntity> categories = catalog.categories(tenantId, outletId);
        List<ProductEntity> products = catalog.products(tenantId, outletId).stream()
                .filter(product -> categories.stream().anyMatch(category -> category.getId().equals(product.getCategory().getId()))).toList();
        List<OptionGroupEntity> groups = new ArrayList<>();
        List<ProductOptionEntity> options = new ArrayList<>();
        for (ProductEntity product : products) {
            List<OptionGroupEntity> productGroups = catalog.groups(tenantId, outletId, product.getId());
            groups.addAll(productGroups);
            for (OptionGroupEntity group : productGroups) {
                options.addAll(catalog.options(tenantId, outletId, group.getId()));
            }
        }
        return FnbMenuMapper.menu(qr, categories, products, groups, options);
    }

    private DiningTableEntity table(UUID tenant, UUID outlet, UUID id) {
        return tables.find(tenant, outlet, id).orElseThrow(ResourceNotFoundException::new);
    }
    private TableQrTokenEntity qr(UUID tenant, UUID outlet, UUID id) {
        return tables.byTable(tenant, outlet, id).orElseThrow(ResourceNotFoundException::new);
    }
    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
