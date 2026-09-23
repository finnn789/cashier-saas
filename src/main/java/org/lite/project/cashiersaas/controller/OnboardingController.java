package org.lite.project.cashiersaas.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.AccountPrincipal;
import org.lite.project.cashiersaas.dto.request.identity.BusinessProfileRequest;
import org.lite.project.cashiersaas.dto.response.identity.RegisterOwnerResponse;
import org.lite.project.cashiersaas.dto.response.identity.FreeTierStartResponse;
import org.lite.project.cashiersaas.service.FreeTierOnboardingService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final FreeTierOnboardingService onboardingService;

    @PostMapping("/freetier/laundry")
    public ApiResponse<FreeTierStartResponse> startFreeTier(
            @AuthenticationPrincipal AccountPrincipal accountPrincipal,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.created("Onboarding Freetier dimulai.",
                onboardingService.start(accountPrincipal, idempotencyKey));
    }

    @PostMapping("/{tenantId}/business-profile")
    public ApiResponse<RegisterOwnerResponse> completeBusinessProfile(
            @AuthenticationPrincipal AccountPrincipal accountPrincipal,
            @PathVariable java.util.UUID tenantId,
            @Valid @RequestBody BusinessProfileRequest request
    ) {
        RegisterOwnerResponse response = onboardingService.complete(accountPrincipal, tenantId, request);
        return ApiResponse.created("Profil bisnis berhasil disimpan.", response);
    }
}
