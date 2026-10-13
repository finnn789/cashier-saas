package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.FnbKitchenMapper;
import org.lite.project.cashiersaas.dto.response.FnbKitchenOrderResponse;
import org.lite.project.cashiersaas.dto.response.FnbKitchenPageResponse;
import org.lite.project.cashiersaas.entity.fnb.OrderItemEntity;
import org.lite.project.cashiersaas.entity.fnb.OrderItemOptionEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbKitchenRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.repository.MerchantReferenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FnbKitchenService {
    private final MerchantAccessGuard guard;
    private final FnbKitchenRepository kitchenOrders;
    private final FnbOrderRepository orders;
    private final MerchantReferenceRepository references;
    private final AuditLogRepository auditLogs;
    private final FnbTableReconciliationService tableReconciliation;
    private final FnbRealtimePublisher realtime;

    @Transactional(readOnly = true)
    public FnbKitchenPageResponse list(TenantPrincipal principal, UUID outletId, int page, int size) {
        if (page < 0 || page > 1_000_000 || size < 1 || size > 50)
            throw new InvalidOperationException("Parameter halaman tidak valid.");
        guard.requireOutlet(principal, outletId);
        List<SalesOrderEntity> queue = kitchenOrders.page(principal.getTenantId(), outletId, page, size);
        FnbKitchenPageResponse response = new FnbKitchenPageResponse();
        response.setOrders(responses(principal.getTenantId(), outletId, queue));
        response.setPage(page);
        response.setSize(size);
        response.setTotal(kitchenOrders.count(principal.getTenantId(), outletId));
        return response;
    }

    @Transactional
    public FnbKitchenOrderResponse prepare(TenantPrincipal principal, UUID outletId, UUID orderId) {
        return transition(principal, outletId, orderId, "PREPARING");
    }

    @Transactional
    public FnbKitchenOrderResponse ready(TenantPrincipal principal, UUID outletId, UUID orderId) {
        return transition(principal, outletId, orderId, "READY");
    }

    @Transactional
    public FnbKitchenOrderResponse complete(TenantPrincipal principal, UUID outletId, UUID orderId) {
        return transition(principal, outletId, orderId, "COMPLETED");
    }

    private FnbKitchenOrderResponse transition(TenantPrincipal principal, UUID outletId, UUID orderId,
                                               String targetStatus) {
        orders.lockTenant(principal.getTenantId());
        MerchantAccessContext context = guard.requireOutlet(principal, outletId);
        SalesOrderEntity order = orders.byId(principal.getTenantId(), outletId, orderId)
                .orElseThrow(ResourceNotFoundException::new);
        orders.refreshOrder(order);
        String previousStatus = order.getStatus().name();
        if ("PREPARING".equals(targetStatus)) {
            order.startPreparing();
        } else if ("READY".equals(targetStatus)) {
            order.markReady();
        } else {
            order.complete();
        }
        auditLogs.persist(AuditLogEntity.recordAction(context.getTenant(), context.getOutlet(),
                references.user(principal.getUserId()), AuditLogEntity.Action.FNB_ORDER_STATUS_CHANGED,
                order.getId(), null, previousStatus + " -> " + order.getStatus().name()));
        orders.flush();
        if ("COMPLETED".equals(targetStatus)) {
            tableReconciliation.reconcile(order);
        }
        realtime.orderChanged("ORDER_" + targetStatus, order);
        return responses(principal.getTenantId(), outletId, List.of(order)).get(0);
    }

    private List<FnbKitchenOrderResponse> responses(UUID tenantId, UUID outletId, List<SalesOrderEntity> queue) {
        if (queue.isEmpty()) {
            return List.of();
        }
        List<UUID> orderIds = queue.stream().map(SalesOrderEntity::getId).toList();
        List<OrderItemEntity> items = orders.items(tenantId, outletId, orderIds);
        Map<UUID, List<OrderItemEntity>> itemsByOrder = items.stream()
                .collect(Collectors.groupingBy(item -> item.getSalesOrder().getId()));
        List<UUID> itemIds = items.stream().map(OrderItemEntity::getId).toList();
        Map<UUID, List<OrderItemOptionEntity>> optionsByItem = orders.itemOptions(tenantId, outletId, itemIds).stream()
                .collect(Collectors.groupingBy(option -> option.getOrderItem().getId()));
        return queue.stream().map(order -> {
            List<OrderItemEntity> orderItems = itemsByOrder.getOrDefault(order.getId(), List.of());
            List<OrderItemOptionEntity> orderOptions = orderItems.stream()
                    .flatMap(item -> optionsByItem.getOrDefault(item.getId(), List.of()).stream()).toList();
            return FnbKitchenMapper.order(order, orderItems, orderOptions);
        }).toList();
    }
}
