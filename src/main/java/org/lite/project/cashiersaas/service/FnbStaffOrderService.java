package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
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

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FnbStaffOrderService {
    private static final int MAX_DAYS = 366;
    private final MerchantAccessGuard guard;
    private final FnbOrderRepository orders;

    @Transactional(readOnly = true)
    public FnbOrderPageResponse list(TenantPrincipal principal, UUID outletId, int page, int size,
                                     OrderStatus status, PaymentStatus paymentStatus, OrderSource source,
                                     LocalDate from, LocalDate to, String orderNumber) {
        if (page < 0 || page > 1_000_000 || size < 1 || size > 50)
            throw new InvalidOperationException("Parameter halaman tidak valid.");
        MerchantAccessContext context = guard.requireOutlet(principal, outletId);
        if ((from == null) != (to == null)) throw new InvalidOperationException("Filter tanggal harus menyertakan from dan to.");
        Instant start = null;
        Instant end = null;
        if (from != null) {
            if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS)
                throw new InvalidOperationException("Rentang tanggal pesanan tidak valid.");
            try {
                ZoneId zone = ZoneId.of(context.getOutlet().getTimeZone());
                start = from.atStartOfDay(zone).toInstant();
                end = to.plusDays(1).atStartOfDay(zone).toInstant();
            } catch (DateTimeException exception) {
                throw new InvalidOperationException("Rentang tanggal pesanan tidak valid.");
            }
        }
        String number = orderNumber == null || orderNumber.isBlank() ? null : orderNumber.trim();
        List<SalesOrderEntity> probe = orders.page(principal.getTenantId(), outletId, page, size,
                status, paymentStatus, source, start, end, number);
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
