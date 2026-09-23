package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.CashierMapper;
import org.lite.project.cashiersaas.dto.request.CashierCatalogItemRequest;
import org.lite.project.cashiersaas.dto.request.CashierPriceChangeRequest;
import org.lite.project.cashiersaas.dto.response.CashierCatalogItemResponse;
import org.lite.project.cashiersaas.entity.laundry.CashierCatalogItemEntity;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.CashierCatalogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CashierCatalogService {
    private final MerchantAccessGuard accessGuard;
    private final CashierCatalogRepository catalogRepository;
    private final AuditLogRepository auditLogRepository;

    @Transactional
    public CashierCatalogItemResponse create(TenantPrincipal principal, UUID outletId, CashierCatalogItemRequest request) {
        MerchantAccessContext context = accessGuard.requireOwner(principal, outletId);
        CashierCatalogItemEntity item = CashierCatalogItemEntity.create(context.getTenant(), context.getOutlet(),
                request.getName().trim(), request.getBillingUnit(), request.getUnitPrice());
        catalogRepository.persist(item);
        return CashierMapper.toCatalogResponse(item);
    }

    @Transactional(readOnly = true)
    public List<CashierCatalogItemResponse> list(TenantPrincipal principal, UUID outletId) {
        accessGuard.requireOutlet(principal, outletId);
        return catalogRepository.listActive(principal.getTenantId(), outletId).stream()
                .map(CashierMapper::toCatalogResponse).toList();
    }

    @Transactional
    public CashierCatalogItemResponse changePrice(TenantPrincipal principal, UUID outletId, UUID itemId,
                                                   CashierPriceChangeRequest request) {
        MerchantAccessContext context = accessGuard.requireOwner(principal, outletId);
        CashierCatalogItemEntity item = catalogRepository.findActive(principal.getTenantId(), outletId, itemId)
                .orElseThrow(ResourceNotFoundException::new);
        String oldPrice = item.getUnitPrice().toPlainString();
        item.changePrice(request.getUnitPrice());
        auditLogRepository.persist(AuditLogEntity.recordAction(context.getTenant(), context.getOutlet(),
                context.getMembership().getUser(), AuditLogEntity.Action.CASHIER_PRICE_CHANGED,
                item.getId(), request.getReason().trim(),
                "old=" + oldPrice + ";new=" + request.getUnitPrice().toPlainString()));
        return CashierMapper.toCatalogResponse(item);
    }

    @Transactional
    public CashierCatalogItemResponse archive(TenantPrincipal principal, UUID outletId, UUID itemId) {
        MerchantAccessContext context = accessGuard.requireOwner(principal, outletId);
        CashierCatalogItemEntity item = catalogRepository.findActive(principal.getTenantId(), outletId, itemId)
                .orElseThrow(ResourceNotFoundException::new);
        item.archive();
        auditLogRepository.persist(AuditLogEntity.recordAction(context.getTenant(), context.getOutlet(),
                context.getMembership().getUser(), AuditLogEntity.Action.CASHIER_ITEM_ARCHIVED,
                item.getId(), null, null));
        return CashierMapper.toCatalogResponse(item);
    }
}
