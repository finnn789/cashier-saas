package org.lite.project.cashiersaas.dto;

import org.lite.project.cashiersaas.dto.response.PlatformInvoiceResponse;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformInvoiceEntity;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformPaymentAttemptEntity;
import org.lite.project.cashiersaas.enums.InvoiceKind;
import org.lite.project.cashiersaas.enums.PlanCode;

public final class PlatformInvoiceMapper {
    private PlatformInvoiceMapper() { }

    /** {@code latestAttempt} may be null for an invoice that never had one. */
    public static PlatformInvoiceResponse toResponse(PlatformInvoiceEntity invoice, PlatformPaymentAttemptEntity latestAttempt,
                                                     boolean mockPaymentEnabled) {
        PlatformInvoiceResponse response = new PlatformInvoiceResponse();
        response.setId(invoice.getId());
        response.setInvoiceNumber(invoice.getInvoiceNumber());
        response.setKind(invoice.getKind());
        response.setStatus(invoice.getStatus());
        response.setPlanCode(invoice.getPlanCode() == null ? null : PlanCode.valueOf(invoice.getPlanCode()));
        response.setBillingCycle(invoice.getBillingCycle());
        response.setPeriodMonths(invoice.getPeriodMonths());
        response.setExtraOutlets(invoice.getExtraOutlets());
        response.setAiQuestions(invoice.getAiQuestions());
        response.setAmountDue(invoice.getAmountDue());
        response.setCurrencyCode(invoice.getCurrencyCode());
        response.setIssuedAt(invoice.getIssuedAt());
        response.setDueAt(invoice.getDueAt());
        response.setPaidAt(invoice.getPaidAt());
        response.setCancelledAt(invoice.getCancelledAt());
        response.setMockPaymentEnabled(mockPaymentEnabled);
        if (latestAttempt != null) {
            response.setProviderCode(latestAttempt.getProviderCode());
            response.setProviderPaymentRef(latestAttempt.getProviderPaymentRef());
        }
        if (invoice.getKind() == InvoiceKind.SUBSCRIPTION && PlatformInvoiceEntity.PAID.equals(invoice.getStatus())) {
            response.setSubscriptionStartsAt(invoice.getSubscription().getStartsAt());
            response.setSubscriptionEndsAt(invoice.getSubscription().getEndsAt());
        }
        return response;
    }
}
