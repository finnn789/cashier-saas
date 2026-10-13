package org.lite.project.cashiersaas.dto;

import org.lite.project.cashiersaas.dto.response.identity.CashierInvitationClaimResponse;
import org.lite.project.cashiersaas.dto.response.identity.CashierInvitationResponse;
import org.lite.project.cashiersaas.dto.response.identity.OnboardingStatusResponse;
import org.lite.project.cashiersaas.entity.shared.identity.CashierInvitationEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.OnboardingIntent;

public final class OnboardingMapper {
    private OnboardingMapper() {
    }

    public static OnboardingStatusResponse toStatus(StaffUserEntity user, TenantEntity pendingTenant) {
        OnboardingStatusResponse response = new OnboardingStatusResponse();
        response.setIntent(user.getOnboardingIntent());
        response.setAccountStatus(user.getStatus());
        if (pendingTenant != null) {
            response.setPendingTenantId(pendingTenant.getId());
            response.setNextStep("COMPLETE_BUSINESS_PROFILE");
        } else if (user.getOnboardingIntent() == null) {
            response.setNextStep("CHOOSE_INTENT");
        } else if (user.isPendingProfile()) {
            response.setNextStep(user.getOnboardingIntent() == OnboardingIntent.OWNER
                    ? "START_FREETIER" : "CLAIM_INVITATION");
        } else {
            response.setNextStep("SELECT_MERCHANT_OUTLET");
        }
        return response;
    }

    public static CashierInvitationResponse toInvitation(CashierInvitationEntity invitation, String code) {
        CashierInvitationResponse response = new CashierInvitationResponse();
        response.setTenantId(invitation.getTenant().getId());
        response.setOutletId(invitation.getOutlet().getId());
        response.setEmail(invitation.getInvitedEmail());
        response.setCode(code);
        response.setExpiresAt(invitation.getExpiresAt());
        return response;
    }

    public static CashierInvitationClaimResponse toClaim(CashierInvitationEntity invitation) {
        CashierInvitationClaimResponse response = new CashierInvitationClaimResponse();
        response.setTenantId(invitation.getTenant().getId());
        response.setOutletId(invitation.getOutlet().getId());
        response.setRole("CASHIER");
        return response;
    }
}
