package org.lite.project.cashiersaas.dto;

import org.lite.project.cashiersaas.dto.response.OutletResponse;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;

public final class CashierMapper {
    private CashierMapper() {
    }

    public static OutletResponse toOutletResponse(OutletEntity outlet) {
        OutletResponse response = new OutletResponse();
        response.setId(outlet.getId());
        response.setName(outlet.getName());
        response.setTimeZone(outlet.getTimeZone());
        response.setAddress(outlet.getAddress());
        response.setPhoneNumber(outlet.getPhoneNumber());
        return response;
    }
}
