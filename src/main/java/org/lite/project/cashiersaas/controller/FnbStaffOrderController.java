package org.lite.project.cashiersaas.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.FnbPosOrderRequest;
import org.lite.project.cashiersaas.dto.response.FnbOrderPageResponse;
import org.lite.project.cashiersaas.dto.response.FnbStaffOrderResponse;
import org.lite.project.cashiersaas.enums.OrderSource;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentStatus;
import org.lite.project.cashiersaas.service.FnbPosOrderService;
import org.lite.project.cashiersaas.service.FnbStaffOrderService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/cashier/outlets/{outletId}/fnb/orders")
public class FnbStaffOrderController {
    private final FnbPosOrderService posOrders;
    private final FnbStaffOrderService staffOrders;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FnbStaffOrderResponse> checkout(@AuthenticationPrincipal TenantPrincipal principal,
                                                       @PathVariable UUID outletId,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                       @Valid @RequestBody FnbPosOrderRequest request) {
        return ApiResponse.created("Pesanan POS dibuat.", posOrders.checkout(principal, outletId, key, request));
    }

    @PutMapping("/{orderId}/items")
    public ApiResponse<FnbStaffOrderResponse> replaceItems(@AuthenticationPrincipal TenantPrincipal principal,
                                                           @PathVariable UUID outletId, @PathVariable UUID orderId,
                                                           @Valid @RequestBody FnbPosOrderRequest request) {
        return ApiResponse.success("Item pesanan diperbarui.", posOrders.replaceItems(principal, outletId, orderId, request));
    }

    @GetMapping
    public ApiResponse<FnbOrderPageResponse> list(@AuthenticationPrincipal TenantPrincipal principal,
                                                   @PathVariable UUID outletId,
                                                   @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
                                                   @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
                                                   @RequestParam(required = false) OrderStatus status,
                                                   @RequestParam(required = false) PaymentStatus paymentStatus,
                                                   @RequestParam(required = false) OrderSource source,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                   @RequestParam(required = false) String orderNumber) {
        return ApiResponse.success("Daftar pesanan diambil.",
                staffOrders.list(principal, outletId, page, size, status, paymentStatus, source, from, to, orderNumber));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<FnbStaffOrderResponse> detail(@AuthenticationPrincipal TenantPrincipal principal,
                                                      @PathVariable UUID outletId, @PathVariable UUID orderId) {
        return ApiResponse.success("Detail pesanan diambil.", staffOrders.detail(principal, outletId, orderId));
    }
}
