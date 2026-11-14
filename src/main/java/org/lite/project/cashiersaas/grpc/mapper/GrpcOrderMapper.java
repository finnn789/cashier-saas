package org.lite.project.cashiersaas.grpc.mapper;

import com.cashier.grpc.v1.GetOrderStatusResponse;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;

@Component
public class GrpcOrderMapper {

    public GetOrderStatusResponse toResponse(SalesOrderEntity order) {
        return toResponse(order, null);
    }

    public GetOrderStatusResponse toResponse(SalesOrderEntity order, String fallbackTenantId) {
        if (order == null) {
            return GetOrderStatusResponse.getDefaultInstance();
        }

        String tenantId = "";
        if (order.getTenant() != null && order.getTenant().getId() != null) {
            tenantId = order.getTenant().getId().toString();
        } else if (fallbackTenantId != null) {
            tenantId = fallbackTenantId;
        }

        String orderNumber = order.getOrderNumber() != null ? order.getOrderNumber() : "";
        String status = order.getStatus() != null ? order.getStatus().name() : "";
        String paymentStatus = order.getPaymentStatus() != null ? order.getPaymentStatus().name() : "";
        String total = (order.getTotal() != null ? order.getTotal() : BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP).toPlainString();
        String createdAt = order.getCreatedAt() != null ? DateTimeFormatter.ISO_INSTANT.format(order.getCreatedAt()) : "";

        return GetOrderStatusResponse.newBuilder()
                .setTenantId(tenantId)
                .setOrderNumber(orderNumber)
                .setStatus(status)
                .setPaymentStatus(paymentStatus)
                .setTotal(total)
                .setCreatedAt(createdAt)
                .build();
    }
}
