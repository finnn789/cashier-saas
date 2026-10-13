package org.lite.project.cashiersaas.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.FnbTableRequest;
import org.lite.project.cashiersaas.dto.response.FnbTableResponse;
import org.lite.project.cashiersaas.dto.response.PublicMenuResponse;
import org.lite.project.cashiersaas.service.FnbTableService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class FnbTableController {
    private final FnbTableService service;

    @PostMapping("/merchant/outlets/{outletId}/fnb/tables") @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FnbTableResponse> create(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId,
                                                 @Valid @RequestBody FnbTableRequest request) {
        return ApiResponse.created("Meja dan QR dibuat.", service.create(principal, outletId, request));
    }
    @PutMapping("/merchant/outlets/{outletId}/fnb/tables/{id}")
    public ApiResponse<FnbTableResponse> update(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId,
                                                 @PathVariable UUID id, @Valid @RequestBody FnbTableRequest request) {
        return ApiResponse.success("Meja diperbarui.", service.update(principal, outletId, id, request));
    }
    @PostMapping("/merchant/outlets/{outletId}/fnb/tables/{id}/rotate-qr")
    public ApiResponse<FnbTableResponse> rotate(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId,
                                                 @PathVariable UUID id) {
        return ApiResponse.success("QR dirotasi.", service.rotate(principal, outletId, id));
    }
    @GetMapping("/merchant/outlets/{outletId}/fnb/tables")
    public ApiResponse<List<FnbTableResponse>> list(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId) {
        return ApiResponse.success("Meja diambil.", service.list(principal, outletId));
    }
    @GetMapping("/public/fnb/menu/{qrToken}")
    public ApiResponse<PublicMenuResponse> menu(@PathVariable String qrToken) {
        return ApiResponse.success("Menu tersedia.", service.publicMenu(qrToken));
    }
}
