package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.FnbStaffOrderMapper;
import org.lite.project.cashiersaas.dto.response.FnbOrderPageResponse;
import org.lite.project.cashiersaas.dto.response.FnbStaffOrderResponse;
import org.lite.project.cashiersaas.entity.fnb.OrderItemEntity;
import org.lite.project.cashiersaas.entity.fnb.OrderItemOptionEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.enums.OrderSource;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentStatus;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FnbStaffOrderService {
    private final MerchantAccessGuard guard;
    private final FnbOrderRepository orders;

    @Transactional(readOnly = true)
    public FnbOrderPageResponse list(TenantPrincipal principal, UUID outletId, int page, int size,
                                     OrderStatus status, PaymentStatus paymentStatus, OrderSource source) {
        if (page < 0 || page > 1_000_000 || size < 1 || size > 50)
            throw new InvalidOperationException("Parameter halaman tidak valid.");
        guard.requireOutlet(principal, outletId);
        List<SalesOrderEntity> probe = orders.page(principal.getTenantId(), outletId, page, size,
                status, paymentStatus, source);
        boolean hasNext = probe.size() > size;
        List<SalesOrderEntity> pageOrders = hasNext ? probe.subList(0, size) : probe;
        FnbOrderPageResponse response = new FnbOrderPageResponse();
        response.setOrders(responses(principal.getTenantId(), outletId, pageOrders));
        response.setPage(page);
        response.setSize(size);
        response.setHasNext(hasNext);
        return response;
    }

    @Transactional(readOnly = true)
    public FnbStaffOrderResponse detail(TenantPrincipal principal, UUID outletId, UUID orderId) {
        guard.requireOutlet(principal, outletId);
        SalesOrderEntity order = orders.byId(principal.getTenantId(), outletId, orderId)
                .orElseThrow(ResourceNotFoundException::new);
        return responses(principal.getTenantId(), outletId, List.of(order)).get(0);
    }

    private List<FnbStaffOrderResponse> responses(UUID tenantId, UUID outletId, List<SalesOrderEntity> ordersForPage) {
        if (ordersForPage.isEmpty()) return List.of();
        List<UUID> orderIds = ordersForPage.stream().map(SalesOrderEntity::getId).toList();
        List<OrderItemEntity> items = orders.items(tenantId, outletId, orderIds);
        Map<UUID, List<OrderItemEntity>> itemsByOrder = items.stream()
                .collect(Collectors.groupingBy(item -> item.getSalesOrder().getId()));
        List<UUID> itemIds = items.stream().map(OrderItemEntity::getId).toList();
        Map<UUID, List<OrderItemOptionEntity>> optionsByItem = orders.itemOptions(tenantId, outletId, itemIds).stream()
                .collect(Collectors.groupingBy(option -> option.getOrderItem().getId()));
        return ordersForPage.stream().map(order -> {
            List<OrderItemEntity> orderItems = itemsByOrder.getOrDefault(order.getId(), List.of());
            List<OrderItemOptionEntity> orderOptions = orderItems.stream()
                    .flatMap(item -> optionsByItem.getOrDefault(item.getId(), List.of()).stream()).toList();
            return FnbStaffOrderMapper.order(order, orderItems, orderOptions);
        }).toList();
    }
}
