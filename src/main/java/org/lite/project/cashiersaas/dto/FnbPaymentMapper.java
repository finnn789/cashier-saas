package org.lite.project.cashiersaas.dto;

import org.lite.project.cashiersaas.dto.response.FnbCancellationResponse;
import org.lite.project.cashiersaas.dto.response.FnbDiscountResponse;
import org.lite.project.cashiersaas.dto.response.FnbPaymentResponse;
import org.lite.project.cashiersaas.dto.response.FnbRefundResponse;
import org.lite.project.cashiersaas.entity.fnb.DiscountEntity;
import org.lite.project.cashiersaas.entity.fnb.PaymentEntity;
import org.lite.project.cashiersaas.entity.fnb.PaymentRefundEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;

public final class FnbPaymentMapper {
    private FnbPaymentMapper() { }

    public static FnbPaymentResponse payment(PaymentEntity payment) {
        FnbPaymentResponse response = new FnbPaymentResponse();
        response.setOrderId(payment.getSalesOrder().getId());
        response.setMethod(payment.getMethod());
        response.setAmountPaid(payment.getAmountPaid());
        response.setChangeAmount(payment.getChangeAmount());
        response.setMethodLabel(payment.getMethodLabel());
        response.setReceiptNumber(payment.getReceiptNumber());
        response.setPaidAt(payment.getPaidAt());
        return response;
    }

    public static FnbDiscountResponse discount(DiscountEntity discount) {
        FnbDiscountResponse response = new FnbDiscountResponse();
        response.setOrderId(discount.getSalesOrder().getId());
        response.setType(discount.getType());
        response.setValue(discount.getValue());
        response.setAmount(discount.getAmount());
        response.setTotal(discount.getSalesOrder().getTotal());
        response.setReason(discount.getReason());
        return response;
    }

    public static FnbRefundResponse refund(PaymentRefundEntity refund) {
        FnbRefundResponse response = new FnbRefundResponse();
        response.setOrderId(refund.getSalesOrder().getId());
        response.setOrderNumber(refund.getSalesOrder().getOrderNumber());
        response.setPaymentStatus(refund.getSalesOrder().getPaymentStatus());
        response.setAmount(refund.getAmount());
        response.setMethod(refund.getMethod());
        response.setReason(refund.getReason());
        response.setRefundedAt(refund.getRefundedAt());
        response.setRefundedBy(refund.getRefundedBy().getFullName());
        return response;
    }

    public static FnbCancellationResponse cancellation(SalesOrderEntity order) {
        FnbCancellationResponse response = new FnbCancellationResponse();
        response.setOrderId(order.getId());
        response.setOrderNumber(order.getOrderNumber());
        response.setStatus(order.getStatus());
        response.setPaymentStatus(order.getPaymentStatus());
        response.setReason(order.getCancellationReason());
        return response;
    }
}
