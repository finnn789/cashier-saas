package org.lite.project.cashiersaas.dto;

import org.lite.project.cashiersaas.dto.response.FnbKitchenItemResponse;
import org.lite.project.cashiersaas.dto.response.FnbKitchenOptionResponse;
import org.lite.project.cashiersaas.dto.response.FnbKitchenOrderResponse;
import org.lite.project.cashiersaas.entity.fnb.OrderItemEntity;
import org.lite.project.cashiersaas.entity.fnb.OrderItemOptionEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public final class FnbKitchenMapper {
    private FnbKitchenMapper() { }

    public static FnbKitchenOrderResponse order(SalesOrderEntity order, List<OrderItemEntity> items,
                                                List<OrderItemOptionEntity> itemOptions) {
        Map<UUID, List<OrderItemOptionEntity>> optionsByItem = itemOptions.stream()
                .collect(Collectors.groupingBy(option -> option.getOrderItem().getId()));
        FnbKitchenOrderResponse response = new FnbKitchenOrderResponse();
        response.setOrderId(order.getId());
        response.setOrderNumber(order.getOrderNumber());
        response.setSource(order.getSource());
        response.setServiceType(order.getServiceType());
        if (order.getDiningTable() != null) {
            response.setTableId(order.getDiningTable().getId());
            response.setTableName(order.getDiningTable().getName());
        }
        response.setCustomerLabel(order.getCustomerLabel());
        response.setStatus(order.getStatus());
        response.setCreatedAt(order.getCreatedAt());
        response.setItems(items.stream().map(item -> item(item, optionsByItem.getOrDefault(item.getId(), List.of())))
                .toList());
        return response;
    }

    private static FnbKitchenItemResponse item(OrderItemEntity item, List<OrderItemOptionEntity> options) {
        FnbKitchenItemResponse response = new FnbKitchenItemResponse();
        response.setItemId(item.getId());
        response.setProductName(item.getProductNameSnapshot());
        response.setQuantity(item.getQuantity());
        response.setNote(item.getNote());
        response.setOptions(options.stream().map(FnbKitchenMapper::option).toList());
        return response;
    }

    private static FnbKitchenOptionResponse option(OrderItemOptionEntity option) {
        FnbKitchenOptionResponse response = new FnbKitchenOptionResponse();
        response.setName(option.getOptionNameSnapshot());
        return response;
    }
}
