package org.lite.project.cashiersaas.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.*;
import org.lite.project.cashiersaas.dto.response.*;
import org.lite.project.cashiersaas.service.*;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/cashier/outlets/{outletId}/fnb/orders")
public class FnbPaymentController {
    private final FnbPaymentService payments;
    private final MockFnbGatewayService mockGateway;
    private final FnbOrderCancellationService cancellations;

    @PostMapping("/{orderId}/payments")
    public ApiResponse<FnbPaymentResponse> pay(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId, @PathVariable UUID orderId, @Valid @RequestBody FnbPaymentRequest request) {
        return ApiResponse.success("Pembayaran diproses.", payments.pay(principal, outletId, orderId, request));
    }

    @PostMapping("/{orderId}/mock-payments/{attemptId}/resolve")
    public ApiResponse<FnbPaymentResponse> resolve(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId, @PathVariable UUID orderId, @PathVariable UUID attemptId, @Valid @RequestBody FnbMockPaymentResolutionRequest request) {
        return ApiResponse.success("Mock gateway diselesaikan.", mockGateway.resolve(principal, outletId, orderId, attemptId, request));
    }

    @PostMapping("/{orderId}/cancel")
    public ApiResponse<FnbCancellationResponse> cancel(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId, @PathVariable UUID orderId, @Valid @RequestBody FnbCancelOrderRequest request) {
        return ApiResponse.success("Pesanan dibatalkan.", cancellations.cancel(principal, outletId, orderId, request));
    }
}
