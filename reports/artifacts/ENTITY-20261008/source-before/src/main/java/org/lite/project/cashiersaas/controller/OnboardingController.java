package org.lite.project.cashiersaas.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.AccountPrincipal;
import org.lite.project.cashiersaas.dto.request.identity.BusinessProfileRequest;
import org.lite.project.cashiersaas.dto.request.identity.OnboardingIntentRequest;
import org.lite.project.cashiersaas.dto.request.identity.StartFreeTierRequest;
import org.lite.project.cashiersaas.dto.response.identity.RegisterOwnerResponse;
import org.lite.project.cashiersaas.dto.response.identity.FreeTierStartResponse;
import org.lite.project.cashiersaas.dto.response.identity.OnboardingStatusResponse;
import org.lite.project.cashiersaas.service.FreeTierOnboardingService;
import org.lite.project.cashiersaas.service.OnboardingIntentService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final OnboardingIntentService intentService;

    @PutMapping("/intent")
    public ApiResponse<OnboardingStatusResponse> chooseIntent(
            @AuthenticationPrincipal AccountPrincipal principal,
            @Valid @RequestBody OnboardingIntentRequest request) {
        return ApiResponse.success("Jalur pendaftaran disimpan.", intentService.choose(principal, request.getIntent()));
    }

    @GetMapping("/status")
    public ApiResponse<OnboardingStatusResponse> status(@AuthenticationPrincipal AccountPrincipal principal) {
        return ApiResponse.success("Status pendaftaran berhasil diambil.", intentService.status(principal));
    }

    @PostMapping("/freetier")
    public ApiResponse<FreeTierStartResponse> startFreeTier(
            @AuthenticationPrincipal AccountPrincipal accountPrincipal,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody StartFreeTierRequest request
    ) {
        return ApiResponse.created("Onboarding Freetier dimulai.",
                onboardingService.startFreeTier(accountPrincipal, idempotencyKey, request.getBusinessTypeCode()));
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
