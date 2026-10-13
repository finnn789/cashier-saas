package org.lite.project.cashiersaas.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.CashierCatalogItemRequest;
import org.lite.project.cashiersaas.dto.request.CashierPriceChangeRequest;
import org.lite.project.cashiersaas.dto.response.CashierCatalogItemResponse;
import org.lite.project.cashiersaas.service.CashierCatalogService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class CashierCatalogController {
    private final CashierCatalogService catalogService;

    @PostMapping("/merchant/outlets/{outletId}/catalog-items")
    public ApiResponse<CashierCatalogItemResponse> create(@AuthenticationPrincipal TenantPrincipal principal,
                                                           @PathVariable UUID outletId,
                                                           @Valid @RequestBody CashierCatalogItemRequest request) {
        return ApiResponse.created("Layanan kasir dibuat.", catalogService.create(principal, outletId, request));
    }

    @GetMapping("/cashier/outlets/{outletId}/catalog-items")
    public ApiResponse<List<CashierCatalogItemResponse>> list(@AuthenticationPrincipal TenantPrincipal principal,
                                                                @PathVariable UUID outletId) {
        return ApiResponse.success("Katalog layanan berhasil diambil.", catalogService.list(principal, outletId));
    }

    @PutMapping("/merchant/outlets/{outletId}/catalog-items/{itemId}/price")
    public ApiResponse<CashierCatalogItemResponse> changePrice(@AuthenticationPrincipal TenantPrincipal principal,
                                                                @PathVariable UUID outletId, @PathVariable UUID itemId,
                                                                @Valid @RequestBody CashierPriceChangeRequest request) {
        return ApiResponse.success("Harga layanan diperbarui.",
                catalogService.changePrice(principal, outletId, itemId, request));
    }

    @DeleteMapping("/merchant/outlets/{outletId}/catalog-items/{itemId}")
    public ApiResponse<CashierCatalogItemResponse> archive(@AuthenticationPrincipal TenantPrincipal principal,
                                                            @PathVariable UUID outletId, @PathVariable UUID itemId) {
        return ApiResponse.success("Layanan diarsipkan.", catalogService.archive(principal, outletId, itemId));
    }
}
