package org.lite.project.cashiersaas.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.FnbPaymentMapper;
import org.lite.project.cashiersaas.dto.request.FnbDiscountRequest;
import org.lite.project.cashiersaas.dto.response.FnbDiscountResponse;
import org.lite.project.cashiersaas.entity.fnb.DiscountEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.enums.DiscountType;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbDiscountRepository;
import org.lite.project.cashiersaas.repository.FnbGatewayAttemptRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FnbDiscountService {
    private final MerchantAccessGuard guard;
    private final FnbOrderRepository orders;
    private final FnbGatewayAttemptRepository attempts;
    private final FnbDiscountRepository discounts;
    private final AuditLogRepository audit;
    private final FnbRealtimePublisher realtime;

    @Transactional
    public FnbDiscountResponse grant(TenantPrincipal principal, UUID outletId, UUID orderId,
                                     FnbDiscountRequest request) {
        orders.lockTenant(principal.getTenantId());
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        SalesOrderEntity order = orders.byId(principal.getTenantId(), outletId, orderId)
                .orElseThrow(ResourceNotFoundException::new);
        orders.refreshOrder(order);
        if (attempts.pendingForOrder(principal.getTenantId(), outletId, orderId).isPresent()) {
            throw new OperationConflictException("Gateway attempt masih tertunda.");
        }
        if (discounts.byOrder(principal.getTenantId(), outletId, orderId).isPresent()) {
            throw new OperationConflictException("Diskon pesanan sudah ditetapkan.");
        }
        BigDecimal amount = request.getType() == DiscountType.NOMINAL ? request.getValue()
                : order.getSubtotal().multiply(request.getValue()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        if (request.getType() == DiscountType.PERCENTAGE && request.getValue().compareTo(new BigDecimal("100")) > 0) {
            throw new InvalidOperationException("Persentase diskon tidak valid.");
        }
        order.applyDiscount(amount);
        DiscountEntity discount = DiscountEntity.grant(context.getTenant(), context.getOutlet(), order, request.getType(),
                request.getValue(), amount, request.getReason().trim(), context.getMembership().getUser());
        discounts.persist(discount);
        orders.flush();
        audit.persist(AuditLogEntity.recordAction(context.getTenant(), context.getOutlet(), context.getMembership().getUser(),
                AuditLogEntity.Action.FNB_DISCOUNT_GRANTED, order.getId(), request.getReason().trim(),
                "amount=" + amount.toPlainString()));
        realtime.orderChanged("DISCOUNT_APPLIED", order);
        return FnbPaymentMapper.discount(discount);
    }
}
