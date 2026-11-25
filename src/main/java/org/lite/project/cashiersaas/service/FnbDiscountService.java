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
import org.lite.project.cashiersaas.enums.Permission;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbDiscountRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FnbDiscountService {
    private final MerchantAccessGuard guard;
    private final FnbOrderRepository orders;
    private final FnbDiscountRepository discounts;
    private final AuditLogRepository audit;

    @Transactional
    public FnbDiscountResponse grant(TenantPrincipal principal, UUID outletId, UUID orderId,
                                     FnbDiscountRequest request) {
        orders.lockOutlet(principal.getTenantId(), outletId);
        MerchantAccessContext context = guard.require(principal, outletId, Permission.ORDER_DISCOUNT);
        SalesOrderEntity order = orders.byId(principal.getTenantId(), outletId, orderId)
                .orElseThrow(ResourceNotFoundException::new);
        orders.refreshOrder(order);
        if (discounts.byOrder(principal.getTenantId(), outletId, orderId).isPresent()) {
            throw new OperationConflictException("Diskon pesanan sudah ditetapkan.");
        }
        BigDecimal amount = request.getType() == DiscountType.NOMINAL ? request.getValue()
                : order.getSubtotal().multiply(request.getValue()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        if (request.getType() == DiscountType.PERCENTAGE && request.getValue().compareTo(new BigDecimal("100")) > 0) {
            throw new InvalidOperationException("Persentase diskon tidak valid.");
        }
        if (context.getMembership().getRole() != Role.OWNERMERCHANT) requireWithinCap(context, order, request, amount);
        order.applyDiscount(amount);
        DiscountEntity discount = DiscountEntity.grant(context.getTenant(), context.getOutlet(), order, request.getType(),
                request.getValue(), amount, request.getReason().trim(), context.getMembership().getUser());
        discounts.persist(discount);
        orders.flush();
        audit.persist(AuditLogEntity.recordAction(context.getTenant(), context.getOutlet(), context.getMembership().getUser(),
                AuditLogEntity.Action.FNB_DISCOUNT_GRANTED, order.getId(), request.getReason().trim(),
                "amount=" + amount.toPlainString()));
        return FnbPaymentMapper.discount(discount);
    }

    // Effective percent = percentage value, or nominal / subtotal * 100; compared as amount vs subtotal * cap / 100.
    private void requireWithinCap(MerchantAccessContext context, SalesOrderEntity order, FnbDiscountRequest request,
                                  BigDecimal amount) {
        BigDecimal cap = context.getTenant().getCashierMaxDiscountPct();
        boolean within = request.getType() == DiscountType.PERCENTAGE
                ? request.getValue().compareTo(cap) <= 0
                : order.getSubtotal().signum() > 0
                        && amount.multiply(new BigDecimal("100")).compareTo(order.getSubtotal().multiply(cap)) <= 0;
        if (!within) {
            throw new InvalidOperationException("Diskon kasir maksimal " + cap.stripTrailingZeros().toPlainString() + "%.");
        }
    }
}
