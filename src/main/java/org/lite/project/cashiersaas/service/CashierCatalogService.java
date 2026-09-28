package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
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
import org.lite.project.cashiersaas.repository.MerchantReferenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CashierCatalogService {
    private final MerchantReferenceRepository references;
    private final CashierCatalogRepository catalogRepository;
    private final AuditLogRepository auditLogRepository;
    private final MerchantAccessGuard merchantAccessGuard;

    @Transactional
    public CashierCatalogItemResponse create(TenantPrincipal principal, UUID outletId, CashierCatalogItemRequest request) {
        merchantAccessGuard.requireOwner(principal, outletId);
        CashierCatalogItemEntity item = CashierCatalogItemEntity.create(references.tenant(principal.getTenantId()),
                references.outlet(outletId),
                request.getName().trim(), request.getBillingUnit(), request.getUnitPrice());
        catalogRepository.persist(item);
        return CashierMapper.toCatalogResponse(item);
    }

    @Transactional(readOnly = true)
    public List<CashierCatalogItemResponse> list(TenantPrincipal principal, UUID outletId) {
        merchantAccessGuard.requireOutlet(principal, outletId);
        return catalogRepository.listActive(principal.getTenantId(), outletId).stream()
                .map(CashierMapper::toCatalogResponse).toList();
    }

    @Transactional
    public CashierCatalogItemResponse changePrice(TenantPrincipal principal, UUID outletId, UUID itemId,
                                                   CashierPriceChangeRequest request) {
        merchantAccessGuard.requireOwner(principal, outletId);
        CashierCatalogItemEntity item = catalogRepository.findActive(principal.getTenantId(), outletId, itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Layanan tidak ditemukan atau tidak dapat diakses."));
        String oldPrice = item.getUnitPrice().toPlainString();
        item.changePrice(request.getUnitPrice());
        auditLogRepository.persist(AuditLogEntity.recordAction(references.tenant(principal.getTenantId()),
                references.outlet(outletId), references.user(principal.getUserId()),
                AuditLogEntity.Action.CASHIER_PRICE_CHANGED,
                item.getId(), request.getReason().trim(),
                "old=" + oldPrice + ";new=" + request.getUnitPrice().toPlainString()));
        return CashierMapper.toCatalogResponse(item);
    }

    @Transactional
    public CashierCatalogItemResponse archive(TenantPrincipal principal, UUID outletId, UUID itemId) {
        merchantAccessGuard.requireOwner(principal, outletId);
        CashierCatalogItemEntity item = catalogRepository.findActive(principal.getTenantId(), outletId, itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Layanan tidak ditemukan atau tidak dapat diakses."));
        item.archive();
        auditLogRepository.persist(AuditLogEntity.recordAction(references.tenant(principal.getTenantId()),
                references.outlet(outletId), references.user(principal.getUserId()),
                AuditLogEntity.Action.CASHIER_ITEM_ARCHIVED,
                item.getId(), null, null));
        return CashierMapper.toCatalogResponse(item);
    }
}
