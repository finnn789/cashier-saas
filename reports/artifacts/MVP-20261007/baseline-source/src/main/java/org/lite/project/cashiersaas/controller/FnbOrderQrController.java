package org.lite.project.cashiersaas.controller;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.response.FnbQrResponse;
import org.lite.project.cashiersaas.service.FnbOrderQrService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/merchant/outlets/{outletId}/fnb/outlet-qr")
public class FnbOrderQrController {
    private final FnbOrderQrService qr;

    @GetMapping
    public ApiResponse<FnbQrResponse> get(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId) {
        return ApiResponse.success("QR outlet.", qr.get(principal, outletId));
    }

    @PutMapping
    public ApiResponse<FnbQrResponse> create(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId) {
        return ApiResponse.success("QR outlet.", qr.outletQr(principal, outletId, false));
    }

    @PostMapping("/rotate")
    public ApiResponse<FnbQrResponse> rotate(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId) {
        return ApiResponse.success("QR outlet diperbarui.", qr.outletQr(principal, outletId, true));
    }
}
