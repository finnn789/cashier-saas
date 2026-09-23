package org.lite.project.cashiersaas.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.CreateCashierRequest;
import org.lite.project.cashiersaas.dto.response.CashierAccountResponse;
import org.lite.project.cashiersaas.service.CashierAccountService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/merchant/outlets/{outletId}/cashiers")
public class CashierAccountController {
    private final CashierAccountService accountService;

    @PostMapping
    public ApiResponse<CashierAccountResponse> create(@AuthenticationPrincipal TenantPrincipal principal,
                                                       @PathVariable UUID outletId,
                                                       @Valid @RequestBody CreateCashierRequest request) {
        return ApiResponse.created("Akun kasir dibuat.", accountService.create(principal, outletId, request));
    }
}
