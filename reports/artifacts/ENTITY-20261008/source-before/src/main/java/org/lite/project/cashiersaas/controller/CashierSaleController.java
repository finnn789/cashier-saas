package org.lite.project.cashiersaas.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.CashierSaleRequest;
import org.lite.project.cashiersaas.dto.response.CashierSaleResponse;
import org.lite.project.cashiersaas.service.CashierSaleService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/cashier/outlets/{outletId}/sales")
public class CashierSaleController {
    private final CashierSaleService saleService;

    @PostMapping
    public ApiResponse<CashierSaleResponse> checkout(@AuthenticationPrincipal TenantPrincipal principal,
                                                      @PathVariable UUID outletId,
                                                      @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                      @Valid @RequestBody CashierSaleRequest request) {
        return ApiResponse.created("Transaksi dan pembayaran berhasil dicatat.",
                saleService.checkout(principal, outletId, key, request));
    }

    @GetMapping("/{saleId}")
    public ApiResponse<CashierSaleResponse> get(@AuthenticationPrincipal TenantPrincipal principal,
                                                 @PathVariable UUID outletId, @PathVariable UUID saleId) {
        return ApiResponse.success("Transaksi berhasil diambil.", saleService.get(principal, outletId, saleId));
    }

    @GetMapping
    public ApiResponse<List<CashierSaleResponse>> list(@AuthenticationPrincipal TenantPrincipal principal,
                                                        @PathVariable UUID outletId) {
        return ApiResponse.success("Riwayat transaksi berhasil diambil.", saleService.list(principal, outletId));
    }
}
