package org.lite.project.cashiersaas.dto;

import org.lite.project.cashiersaas.dto.response.identity.MerchantOutletChoiceResponse;
import org.lite.project.cashiersaas.dto.response.identity.MerchantStaffResponse;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;

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

    public static MerchantStaffResponse toStaff(TenantMembershipEntity membership) {
        StaffUserEntity user = membership.getUser();
        MerchantStaffResponse response = new MerchantStaffResponse();
        response.setUserId(user.getId());
        response.setFullName(user.getFullName());
        response.setEmail(user.getEmail());
        response.setPhoneNumber(user.getPhoneNumber());
        response.setActive(membership.isActive());
        response.setJoinedAt(membership.getCreatedAt());
        return response;
    }
}
