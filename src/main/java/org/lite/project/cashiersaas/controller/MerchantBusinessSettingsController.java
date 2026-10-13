package org.lite.project.cashiersaas.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.MerchantBusinessSettingsRequest;
import org.lite.project.cashiersaas.dto.response.MerchantBusinessSettingsResponse;
import org.lite.project.cashiersaas.service.MerchantBusinessSettingsService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/merchant/outlets/{outletId}/business-settings")
public class MerchantBusinessSettingsController {
    private final MerchantBusinessSettingsService service;

    @GetMapping
    public ApiResponse<MerchantBusinessSettingsResponse> get(@AuthenticationPrincipal TenantPrincipal principal,
                                                              @PathVariable UUID outletId) {
        return ApiResponse.success("Pengaturan bisnis diambil.", service.get(principal, outletId));
    }

    @PutMapping
    public ApiResponse<MerchantBusinessSettingsResponse> update(@AuthenticationPrincipal TenantPrincipal principal,
                                                                 @PathVariable UUID outletId,
                                                                 @Valid @RequestBody MerchantBusinessSettingsRequest request) {
        return ApiResponse.success("Pengaturan bisnis diperbarui.", service.update(principal, outletId, request));
    }
}
