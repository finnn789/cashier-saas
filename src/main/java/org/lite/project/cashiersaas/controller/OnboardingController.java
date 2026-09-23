package org.lite.project.cashiersaas.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.AccountPrincipal;
import org.lite.project.cashiersaas.dto.request.identity.BusinessProfileRequest;
import org.lite.project.cashiersaas.dto.response.identity.RegisterOwnerResponse;
import org.lite.project.cashiersaas.service.RegisterService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final RegisterService registerService;

    @PostMapping("/business-profile")
    public ApiResponse<RegisterOwnerResponse> completeBusinessProfile(
            @AuthenticationPrincipal AccountPrincipal accountPrincipal,
            @Valid @RequestBody BusinessProfileRequest request
    ) {
        RegisterOwnerResponse response = registerService.completeBusinessProfile(accountPrincipal, request);
        return ApiResponse.created("Profil bisnis berhasil disimpan.", response);
    }
}
