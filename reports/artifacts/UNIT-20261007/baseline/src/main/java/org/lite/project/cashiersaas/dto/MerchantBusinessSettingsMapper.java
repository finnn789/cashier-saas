package org.lite.project.cashiersaas.dto;

import org.lite.project.cashiersaas.dto.response.MerchantBusinessSettingsResponse;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;

public final class MerchantBusinessSettingsMapper {
    private MerchantBusinessSettingsMapper() { }

    public static MerchantBusinessSettingsResponse response(TenantEntity tenant, OutletEntity outlet) {
        MerchantBusinessSettingsResponse response = new MerchantBusinessSettingsResponse();
        response.setOutletId(outlet.getId());
        response.setBusinessName(tenant.getName());
        response.setLogoUrl(tenant.getLogoUrl());
        response.setCurrency(tenant.getCurrency());
        response.setAddress(tenant.getAddress());
        response.setPhoneNumber(tenant.getPhoneNumber());
        response.setTaxRate(tenant.getTaxRate());
        response.setServiceChargeRate(tenant.getServiceChargeRate());
        response.setOutletName(outlet.getName());
        response.setOutletAddress(outlet.getAddress());
        response.setOutletPhoneNumber(outlet.getPhoneNumber());
        response.setTimeZone(outlet.getTimeZone());
        return response;
    }
}
