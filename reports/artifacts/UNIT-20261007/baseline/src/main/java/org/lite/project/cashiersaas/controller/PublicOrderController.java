package org.lite.project.cashiersaas.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.dto.request.PublicOrderRequest;
import org.lite.project.cashiersaas.dto.response.PublicOrderResponse;
import org.lite.project.cashiersaas.service.PublicOrderService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/public/fnb")
public class PublicOrderController {
    private final PublicOrderService service;

    @PostMapping("/menu/{qrToken}/orders") @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PublicOrderResponse> checkout(@PathVariable String qrToken,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PublicOrderRequest request) {
        return ApiResponse.created("Pesanan diterima.", service.checkout(qrToken, idempotencyKey, request));
    }
    @GetMapping("/orders/{orderToken}/status")
    public ApiResponse<PublicOrderResponse> status(@PathVariable String orderToken) {
        return ApiResponse.success("Status pesanan diambil.", service.status(orderToken));
    }
}
