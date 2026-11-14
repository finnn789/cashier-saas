package org.lite.project.cashiersaas.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.FnbInventoryItemRequest;
import org.lite.project.cashiersaas.dto.request.FnbStockCountRequest;
import org.lite.project.cashiersaas.dto.request.FnbStockMovementRequest;
import org.lite.project.cashiersaas.dto.response.FnbInventoryItemResponse;
import org.lite.project.cashiersaas.dto.response.FnbStockCountResponse;
import org.lite.project.cashiersaas.dto.response.FnbStockMovementResponse;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryBalanceEntity;
import org.lite.project.cashiersaas.service.FnbInventoryService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class FnbInventoryController {
    private final FnbInventoryService service;

    @PostMapping("/merchant/outlets/{outletId}/fnb/inventory/items")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FnbInventoryItemResponse> createItem(@AuthenticationPrincipal TenantPrincipal principal,
                                                            @PathVariable UUID outletId,
                                                            @Valid @RequestBody FnbInventoryItemRequest request) {
        return ApiResponse.created("Bahan dibuat.", service.createItem(principal, outletId, request));
    }

    @PutMapping("/merchant/outlets/{outletId}/fnb/inventory/items/{id}")
    public ApiResponse<FnbInventoryItemResponse> updateItem(@AuthenticationPrincipal TenantPrincipal principal,
                                                            @PathVariable UUID outletId,
                                                            @PathVariable UUID id,
                                                            @Valid @RequestBody FnbInventoryItemRequest request) {
        return ApiResponse.success("Bahan diperbarui.", service.updateItem(principal, outletId, id, request));
    }

    @DeleteMapping("/merchant/outlets/{outletId}/fnb/inventory/items/{id}")
    public ApiResponse<FnbInventoryItemResponse> deactivateItem(@AuthenticationPrincipal TenantPrincipal principal,
                                                                @PathVariable UUID outletId,
                                                                @PathVariable UUID id) {
        return ApiResponse.success("Bahan dinonaktifkan.", service.deactivateItem(principal, outletId, id));
    }

    @GetMapping("/cashier/outlets/{outletId}/fnb/inventory/items")
    public ApiResponse<List<FnbInventoryItemResponse>> items(@AuthenticationPrincipal TenantPrincipal principal,
                                                             @PathVariable UUID outletId,
                                                             @RequestParam(required = false) InventoryBalanceEntity.Status status) {
        return ApiResponse.success("Bahan diambil.", service.items(principal, outletId, status));
    }

    @PostMapping("/cashier/outlets/{outletId}/fnb/inventory/items/{id}/movements")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FnbStockMovementResponse> recordMovement(@AuthenticationPrincipal TenantPrincipal principal,
                                                                @PathVariable UUID outletId,
                                                                @PathVariable UUID id,
                                                                @Valid @RequestBody FnbStockMovementRequest request) {
        return ApiResponse.created("Mutasi stok dicatat.", service.recordMovement(principal, outletId, id, request));
    }

    @GetMapping("/cashier/outlets/{outletId}/fnb/inventory/items/{id}/movements")
    public ApiResponse<List<FnbStockMovementResponse>> movements(@AuthenticationPrincipal TenantPrincipal principal,
                                                                 @PathVariable UUID outletId,
                                                                 @PathVariable UUID id,
                                                                 @RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success("Mutasi stok diambil.", service.movements(principal, outletId, id, page, size));
    }

    @PostMapping("/merchant/outlets/{outletId}/fnb/inventory/stock-counts")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FnbStockCountResponse> stockCount(@AuthenticationPrincipal TenantPrincipal principal,
                                                         @PathVariable UUID outletId,
                                                         @Valid @RequestBody FnbStockCountRequest request) {
        return ApiResponse.created("Stok opname dicatat.", service.stockCount(principal, outletId, request));
    }
}
