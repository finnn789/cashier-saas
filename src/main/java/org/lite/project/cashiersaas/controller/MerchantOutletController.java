package org.lite.project.cashiersaas.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.MerchantOutletRequest;
import org.lite.project.cashiersaas.dto.response.MerchantOutletListResponse;
import org.lite.project.cashiersaas.dto.response.MerchantOutletResponse;
import org.lite.project.cashiersaas.service.OutletService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/merchant/outlets")
public class MerchantOutletController {
    private final OutletService outletService;

    @GetMapping
    public ApiResponse<MerchantOutletListResponse> list(@AuthenticationPrincipal TenantPrincipal principal) {
        return ApiResponse.success("Daftar outlet diambil.", outletService.listAll(principal));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MerchantOutletResponse> create(@AuthenticationPrincipal TenantPrincipal principal,
                                                      @Valid @RequestBody MerchantOutletRequest request) {
        return ApiResponse.created("Outlet dibuat.", outletService.create(principal, request));
    }

    @PutMapping("/{outletId}")
    public ApiResponse<MerchantOutletResponse> update(@AuthenticationPrincipal TenantPrincipal principal,
                                                      @PathVariable UUID outletId,
                                                      @Valid @RequestBody MerchantOutletRequest request) {
        return ApiResponse.success("Outlet diperbarui.", outletService.update(principal, outletId, request));
    }

    @PostMapping("/{outletId}/deactivate")
    public ApiResponse<MerchantOutletResponse> deactivate(@AuthenticationPrincipal TenantPrincipal principal,
                                                          @PathVariable UUID outletId) {
        return ApiResponse.success("Outlet dinonaktifkan.", outletService.deactivate(principal, outletId));
    }

    @PostMapping("/{outletId}/reactivate")
    public ApiResponse<MerchantOutletResponse> reactivate(@AuthenticationPrincipal TenantPrincipal principal,
                                                          @PathVariable UUID outletId) {
        return ApiResponse.success("Outlet diaktifkan kembali.", outletService.reactivate(principal, outletId));
    }
}
