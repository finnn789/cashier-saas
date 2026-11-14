package org.lite.project.cashiersaas.dto;

import org.lite.project.cashiersaas.dto.response.FnbPaymentLabelResponse;
import org.lite.project.cashiersaas.entity.fnb.PaymentLabelEntity;

public final class FnbPaymentLabelMapper {
    private FnbPaymentLabelMapper() { }

    public static FnbPaymentLabelResponse label(PaymentLabelEntity label) {
        FnbPaymentLabelResponse response = new FnbPaymentLabelResponse();
        response.setId(label.getId());
        response.setName(label.getName());
        response.setActive(label.isActive());
        response.setDisplayOrder(label.getDisplayOrder());
        return response;
    }
}
