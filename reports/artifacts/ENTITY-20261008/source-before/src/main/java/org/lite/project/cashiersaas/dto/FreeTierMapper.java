package org.lite.project.cashiersaas.dto;

import org.lite.project.cashiersaas.dto.response.identity.FreeTierStartResponse;
import org.lite.project.cashiersaas.dto.response.identity.RegisterOwnerResponse;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;

import java.time.Instant;

public final class FreeTierMapper {
    private FreeTierMapper() {
    }

    public static FreeTierStartResponse toStartResponse(TenantEntity tenant) {
        FreeTierStartResponse response = new FreeTierStartResponse();
        response.setTenantId(tenant.getId());
        response.setPlanCode("FREETIER");
        response.setBusinessType(tenant.getBusinessType().getCode());
        response.setTenantStatus(tenant.getStatus());
        return response;
    }

    public static RegisterOwnerResponse toOwnerResponse(String token, Instant expiresAt,
                                                         TenantMembershipEntity membership, OutletEntity outlet) {
        return RegisterOwnerResponse.fromBusinessProfile(token, expiresAt,
                membership.getTenant().getId(), outlet.getId(), membership.getRole().name());
    }
}
