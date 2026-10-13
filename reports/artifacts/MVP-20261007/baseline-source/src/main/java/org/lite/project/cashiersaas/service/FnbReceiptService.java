package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.FnbReceiptMapper;
import org.lite.project.cashiersaas.dto.response.FnbReceiptResponse;
import org.lite.project.cashiersaas.entity.fnb.OrderItemEntity;
import org.lite.project.cashiersaas.entity.fnb.PaymentEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentStatus;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.repository.FnbPaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FnbReceiptService {
    private final MerchantAccessGuard guard;
    private final FnbOrderRepository orders;
    private final FnbPaymentRepository payments;
    @Transactional(readOnly = true)
    public FnbReceiptResponse receipt(TenantPrincipal principal, UUID outletId, UUID orderId) {
        MerchantAccessContext context = guard.requireOutlet(principal, outletId);
        try { ZoneId.of(context.getOutlet().getTimeZone()); } catch (DateTimeException exception) { throw new InvalidOperationException("Zona waktu outlet tidak valid."); }
        SalesOrderEntity order = orders.byId(principal.getTenantId(), outletId, orderId).orElseThrow(ResourceNotFoundException::new);
        if (order.getPaymentStatus() != PaymentStatus.PAID || order.getStatus() == OrderStatus.CANCELLED) throw new ResourceNotFoundException();
        PaymentEntity payment = payments.byOrder(principal.getTenantId(), outletId, orderId).orElseThrow(ResourceNotFoundException::new);
        List<OrderItemEntity> items = orders.items(principal.getTenantId(), outletId, List.of(orderId));
        return FnbReceiptMapper.receipt(payment, items, orders.itemOptions(principal.getTenantId(), outletId, items.stream().map(OrderItemEntity::getId).toList()));
    }
}
