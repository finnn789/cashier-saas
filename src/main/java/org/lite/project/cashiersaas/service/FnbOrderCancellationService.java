package org.lite.project.cashiersaas.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.FnbPaymentMapper;
import org.lite.project.cashiersaas.dto.request.FnbCancelOrderRequest;
import org.lite.project.cashiersaas.dto.response.FnbCancellationResponse;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.exception.runtime.AccessForbiddenException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbDiscountRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FnbOrderCancellationService {
    private final MerchantAccessGuard guard;
    private final FnbOrderRepository orders;
    private final FnbDiscountRepository discounts;
    private final AuditLogRepository audit;

    @Transactional
    public FnbCancellationResponse cancel(TenantPrincipal principal, UUID outletId, UUID orderId,
                                          FnbCancelOrderRequest request) {
        orders.lockTenant(principal.getTenantId());
        MerchantAccessContext context = guard.requireOutlet(principal, outletId);
        SalesOrderEntity order = orders.byId(principal.getTenantId(), outletId, orderId)
                .orElseThrow(ResourceNotFoundException::new);
        orders.refreshOrder(order);
        if (context.getMembership().getRole() == Role.CASHIER
                && discounts.byOrder(principal.getTenantId(), outletId, orderId).isPresent()) {
            throw new AccessForbiddenException("Pesanan dengan diskon hanya dapat dibatalkan oleh owner.");
        }
        order.cancel(request.getReason());
        audit.persist(AuditLogEntity.recordAction(context.getTenant(), context.getOutlet(), context.getMembership().getUser(),
                AuditLogEntity.Action.FNB_ORDER_CANCELLED, order.getId(), order.getCancellationReason(), null));
        orders.flush();
        return FnbPaymentMapper.cancellation(order);
    }
}
