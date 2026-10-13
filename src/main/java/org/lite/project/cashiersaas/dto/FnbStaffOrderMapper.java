package org.lite.project.cashiersaas.dto;

import org.lite.project.cashiersaas.dto.response.FnbStaffOrderItemResponse;
import org.lite.project.cashiersaas.dto.response.FnbStaffOrderOptionResponse;
import org.lite.project.cashiersaas.dto.response.FnbStaffOrderResponse;
import org.lite.project.cashiersaas.entity.fnb.OrderItemEntity;
import org.lite.project.cashiersaas.entity.fnb.OrderItemOptionEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public final class FnbStaffOrderMapper {
    private FnbStaffOrderMapper() { }

    public static FnbStaffOrderResponse order(SalesOrderEntity order, List<OrderItemEntity> items,
                                              List<OrderItemOptionEntity> itemOptions) {
        Map<UUID, List<OrderItemOptionEntity>> optionsByItem = itemOptions.stream()
                .collect(Collectors.groupingBy(option -> option.getOrderItem().getId()));
        FnbStaffOrderResponse response = new FnbStaffOrderResponse();
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
        response.setPaymentStatus(order.getPaymentStatus());
        response.setCreatedAt(order.getCreatedAt());
        response.setSubtotal(order.getSubtotal());
        response.setTaxRate(order.getTaxRateSnapshot());
        response.setTaxAmount(order.getTaxAmount());
        response.setServiceChargeRate(order.getServiceChargeRateSnapshot());
        response.setServiceChargeAmount(order.getServiceChargeAmount());
        response.setDiscountAmount(order.getDiscountAmount());
        response.setTotal(order.getTotal());
        response.setItems(items.stream().map(item -> item(item,
                optionsByItem.getOrDefault(item.getId(), List.of()))).toList());
        return response;
    }

    private static FnbStaffOrderItemResponse item(OrderItemEntity item, List<OrderItemOptionEntity> options) {
        FnbStaffOrderItemResponse response = new FnbStaffOrderItemResponse();
        response.setItemId(item.getId());
        response.setProductName(item.getProductNameSnapshot());
        response.setUnitPrice(item.getUnitPriceSnapshot());
        response.setQuantity(item.getQuantity());
        response.setLineTotal(item.getLineTotal());
        response.setNote(item.getNote());
        response.setOptions(options.stream().map(FnbStaffOrderMapper::option).toList());
        return response;
    }

    private static FnbStaffOrderOptionResponse option(OrderItemOptionEntity option) {
        FnbStaffOrderOptionResponse response = new FnbStaffOrderOptionResponse();
        if (option.getProductOption() != null) response.setOptionId(option.getProductOption().getId());
        response.setName(option.getOptionNameSnapshot());
        response.setPriceAdjustment(option.getPriceAdjustmentSnapshot());
        return response;
    }
}
