package org.lite.project.cashiersaas.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.FnbPaymentLabelRequest;
import org.lite.project.cashiersaas.dto.response.FnbPaymentLabelResponse;
import org.lite.project.cashiersaas.service.FnbPaymentLabelService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class FnbPaymentLabelController {
    private final FnbPaymentLabelService service;

    @GetMapping("/merchant/outlets/{outletId}/fnb/payment-labels")
    public ApiResponse<List<FnbPaymentLabelResponse>> list(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId) {
        return ApiResponse.success("Label pembayaran diambil.", service.list(principal, outletId, false));
    }

    @PostMapping("/merchant/outlets/{outletId}/fnb/payment-labels")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FnbPaymentLabelResponse> create(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId,
                                                       @Valid @RequestBody FnbPaymentLabelRequest request) {
        return ApiResponse.created("Label pembayaran dibuat.", service.create(principal, outletId, request));
    }

    @PutMapping("/merchant/outlets/{outletId}/fnb/payment-labels/{labelId}")
    public ApiResponse<FnbPaymentLabelResponse> update(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId,
                                                       @PathVariable UUID labelId, @Valid @RequestBody FnbPaymentLabelRequest request) {
        return ApiResponse.success("Label pembayaran diperbarui.", service.update(principal, outletId, labelId, request));
    }

    @GetMapping("/cashier/outlets/{outletId}/fnb/payment-labels")
    public ApiResponse<List<FnbPaymentLabelResponse>> active(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId) {
        return ApiResponse.success("Label pembayaran diambil.", service.list(principal, outletId, true));
    }
}
