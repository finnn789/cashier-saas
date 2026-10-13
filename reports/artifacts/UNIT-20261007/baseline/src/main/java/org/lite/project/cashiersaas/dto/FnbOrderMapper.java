package org.lite.project.cashiersaas.dto;

import org.lite.project.cashiersaas.dto.response.PublicOrderResponse;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;

public final class FnbOrderMapper {
    private FnbOrderMapper() { }
    public static PublicOrderResponse publicOrder(SalesOrderEntity order, String rawToken) {
        PublicOrderResponse response = new PublicOrderResponse();
        response.setOrderNumber(order.getOrderNumber()); response.setOrderToken(rawToken);
        response.setStatusTokenExpiresAt(order.getPublicStatusTokenExpiresAt());
        response.setStatus(order.getStatus()); response.setPaymentStatus(order.getPaymentStatus());
        response.setSubtotal(order.getSubtotal()); response.setTaxAmount(order.getTaxAmount());
        response.setServiceChargeAmount(order.getServiceChargeAmount()); response.setTotal(order.getTotal());
        return response;
    }
}
