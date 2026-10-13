package org.lite.project.cashiersaas.dto;

import org.lite.project.cashiersaas.dto.response.FnbCancellationResponse;
import org.lite.project.cashiersaas.dto.response.FnbDiscountResponse;
import org.lite.project.cashiersaas.dto.response.FnbPaymentResponse;
import org.lite.project.cashiersaas.entity.fnb.DiscountEntity;
import org.lite.project.cashiersaas.entity.fnb.FnbGatewayAttemptEntity;
import org.lite.project.cashiersaas.entity.fnb.PaymentEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.enums.FnbGatewayOutcome;
import org.lite.project.cashiersaas.enums.PaymentMethod;

public final class FnbPaymentMapper {
    private FnbPaymentMapper() { }

    public static FnbPaymentResponse payment(PaymentEntity payment) {
        FnbPaymentResponse response = new FnbPaymentResponse();
        response.setOrderId(payment.getSalesOrder().getId());
        response.setMethod(payment.getMethod());
        response.setAmountPaid(payment.getAmountPaid());
        response.setChangeAmount(payment.getChangeAmount());
        response.setReceiptNumber(payment.getReceiptNumber());
        response.setPaidAt(payment.getPaidAt());
        return response;
    }

    public static FnbPaymentResponse attempt(FnbGatewayAttemptEntity attempt) {
        FnbPaymentResponse response = mockMetadata(attempt);
        response.setAmountRequested(attempt.getAmount());
        return response;
    }

    public static FnbPaymentResponse mockPayment(PaymentEntity payment, FnbGatewayAttemptEntity attempt) {
        FnbPaymentResponse response = payment(payment);
        response.setProvider("MOCK");
        response.setAttemptId(attempt.getId());
        response.setGatewayOutcome(FnbGatewayOutcome.SUCCEEDED);
        response.setProviderReference(attempt.getProviderReference());
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

    public static FnbCancellationResponse cancellation(SalesOrderEntity order) {
        FnbCancellationResponse response = new FnbCancellationResponse();
        response.setOrderId(order.getId());
        response.setOrderNumber(order.getOrderNumber());
        response.setStatus(order.getStatus());
        response.setPaymentStatus(order.getPaymentStatus());
        response.setReason(order.getCancellationReason());
        return response;
    }

    private static FnbPaymentResponse mockMetadata(FnbGatewayAttemptEntity attempt) {
        FnbPaymentResponse response = new FnbPaymentResponse();
        response.setOrderId(attempt.getSalesOrder().getId());
        response.setMethod(PaymentMethod.MOCK_GATEWAY);
        response.setProvider("MOCK");
        response.setAttemptId(attempt.getId());
        response.setGatewayOutcome(attempt.getOutcome());
        response.setProviderReference(attempt.getProviderReference());
        return response;
    }
}
