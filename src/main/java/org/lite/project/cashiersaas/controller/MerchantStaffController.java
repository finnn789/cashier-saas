package org.lite.project.cashiersaas.controller;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.response.identity.MerchantStaffPasswordResetResponse;
import org.lite.project.cashiersaas.dto.response.identity.MerchantStaffResponse;
import org.lite.project.cashiersaas.service.MerchantStaffService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/merchant/outlets/{outletId}/staff")
public class MerchantStaffController {
    private final MerchantStaffService staff;

    @GetMapping
    public ApiResponse<List<MerchantStaffResponse>> list(@AuthenticationPrincipal TenantPrincipal principal,
                                                          @PathVariable UUID outletId) {
        return ApiResponse.success("Daftar kasir diambil.", staff.list(principal, outletId));
    }

    @PostMapping("/{userId}/deactivate")
    public ApiResponse<MerchantStaffResponse> deactivate(@AuthenticationPrincipal TenantPrincipal principal,
                                                          @PathVariable UUID outletId, @PathVariable UUID userId) {
        return ApiResponse.success("Kasir dinonaktifkan.", staff.deactivate(principal, outletId, userId));
    }

    @PostMapping("/{userId}/reactivate")
    public ApiResponse<MerchantStaffResponse> reactivate(@AuthenticationPrincipal TenantPrincipal principal,
                                                          @PathVariable UUID outletId, @PathVariable UUID userId) {
        return ApiResponse.success("Kasir diaktifkan kembali.", staff.reactivate(principal, outletId, userId));
    }

    @PostMapping("/{userId}/password-reset")
    public ApiResponse<MerchantStaffPasswordResetResponse> resetPassword(
            @AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId,
            @PathVariable UUID userId) {
        return ApiResponse.success("Password sementara dibuat.", staff.resetPassword(principal, outletId, userId));
    }
}
