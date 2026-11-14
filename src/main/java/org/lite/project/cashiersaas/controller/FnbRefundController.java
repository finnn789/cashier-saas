package org.lite.project.cashiersaas.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.FnbRefundRequest;
import org.lite.project.cashiersaas.dto.response.FnbRefundResponse;
import org.lite.project.cashiersaas.service.FnbRefundService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/merchant/outlets/{outletId}/fnb/orders")
public class FnbRefundController {
    private final FnbRefundService service;

    @PostMapping("/{orderId}/refund")
    public ApiResponse<FnbRefundResponse> refund(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId,
                                                 @PathVariable UUID orderId,
                                                 @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                 @Valid @RequestBody FnbRefundRequest request) {
        return ApiResponse.success("Pesanan direfund.", service.refund(principal, outletId, orderId, key, request));
    }
}
