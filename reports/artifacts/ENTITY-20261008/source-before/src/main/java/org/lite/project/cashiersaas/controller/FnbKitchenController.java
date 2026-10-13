package org.lite.project.cashiersaas.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.response.FnbKitchenOrderResponse;
import org.lite.project.cashiersaas.dto.response.FnbKitchenPageResponse;
import org.lite.project.cashiersaas.service.FnbKitchenService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/cashier/outlets/{outletId}/fnb/kitchen/orders")
public class FnbKitchenController {
    private final FnbKitchenService kitchen;

    @GetMapping
    public ApiResponse<FnbKitchenPageResponse> list(@AuthenticationPrincipal TenantPrincipal principal,
                                                    @PathVariable UUID outletId,
                                                    @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
                                                    @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return ApiResponse.success("Antrean dapur diambil.", kitchen.list(principal, outletId, page, size));
    }

    @PostMapping("/{orderId}/prepare")
    public ApiResponse<FnbKitchenOrderResponse> prepare(@AuthenticationPrincipal TenantPrincipal principal,
                                                        @PathVariable UUID outletId, @PathVariable UUID orderId) {
        return ApiResponse.success("Pesanan mulai disiapkan.", kitchen.prepare(principal, outletId, orderId));
    }

    @PostMapping("/{orderId}/ready")
    public ApiResponse<FnbKitchenOrderResponse> ready(@AuthenticationPrincipal TenantPrincipal principal,
                                                      @PathVariable UUID outletId, @PathVariable UUID orderId) {
        return ApiResponse.success("Pesanan siap disajikan.", kitchen.ready(principal, outletId, orderId));
    }

    @PostMapping("/{orderId}/complete")
    public ApiResponse<FnbKitchenOrderResponse> complete(@AuthenticationPrincipal TenantPrincipal principal,
                                                         @PathVariable UUID outletId, @PathVariable UUID orderId) {
        return ApiResponse.success("Pesanan selesai.", kitchen.complete(principal, outletId, orderId));
    }
}
