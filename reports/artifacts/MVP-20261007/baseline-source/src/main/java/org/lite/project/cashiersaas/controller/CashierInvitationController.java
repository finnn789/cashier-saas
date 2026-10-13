package org.lite.project.cashiersaas.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.AccountPrincipal;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.identity.ClaimCashierInvitationRequest;
import org.lite.project.cashiersaas.dto.request.identity.IssueCashierInvitationRequest;
import org.lite.project.cashiersaas.dto.response.identity.CashierInvitationClaimResponse;
import org.lite.project.cashiersaas.dto.response.identity.CashierInvitationResponse;
import org.lite.project.cashiersaas.service.CashierInvitationService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class CashierInvitationController {
    private final CashierInvitationService invitationService;

    @PostMapping("/api/v1/merchant/outlets/{outletId}/cashier-invitations")
    public ApiResponse<CashierInvitationResponse> issue(@AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable UUID outletId, @Valid @RequestBody IssueCashierInvitationRequest request) {
        return ApiResponse.created("Undangan kasir dibuat.",
                invitationService.issue(principal, outletId, request.getEmail()));
    }

    @PostMapping("/api/v1/onboarding/cashier-invitations/claim")
    public ApiResponse<CashierInvitationClaimResponse> claim(@AuthenticationPrincipal AccountPrincipal principal,
            @Valid @RequestBody ClaimCashierInvitationRequest request) {
        return ApiResponse.success("Undangan kasir berhasil diklaim.",
                invitationService.claim(principal, request.getCode()));
    }
}
