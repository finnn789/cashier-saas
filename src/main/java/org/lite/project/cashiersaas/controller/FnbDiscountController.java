package org.lite.project.cashiersaas.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.FnbDiscountRequest;
import org.lite.project.cashiersaas.dto.response.FnbDiscountResponse;
import org.lite.project.cashiersaas.service.FnbDiscountService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class FnbDiscountController {
    private final FnbDiscountService service;

    @PostMapping({"/merchant/outlets/{outletId}/fnb/orders/{orderId}/discount", "/cashier/outlets/{outletId}/fnb/orders/{orderId}/discount"})
    public ApiResponse<FnbDiscountResponse> discount(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId, @PathVariable UUID orderId, @Valid @RequestBody FnbDiscountRequest request) {
        return ApiResponse.success("Diskon diterapkan.", service.grant(principal, outletId, orderId, request));
    }
}
