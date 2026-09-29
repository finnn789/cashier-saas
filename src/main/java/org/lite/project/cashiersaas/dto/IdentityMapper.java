package org.lite.project.cashiersaas.dto;

import org.lite.project.cashiersaas.dto.response.identity.MerchantOutletChoiceResponse;

public final class IdentityMapper {
    private IdentityMapper() {
    }

    public static MerchantOutletChoiceResponse toChoice(MerchantOutletChoiceProjection choice) {
        MerchantOutletChoiceResponse response = new MerchantOutletChoiceResponse();
        response.setTenantId(choice.getTenantId());
        response.setBusinessName(choice.getBusinessName());
        response.setOutletId(choice.getOutletId());
        response.setOutletName(choice.getOutletName());
        response.setRole(choice.getRole());
        return response;
    }
}
