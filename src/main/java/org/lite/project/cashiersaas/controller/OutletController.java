package org.lite.project.cashiersaas.controller;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.response.OutletResponse;
import org.lite.project.cashiersaas.service.OutletService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/cashier/outlets")
public class OutletController {
    private final OutletService outletService;

    @GetMapping
    public ApiResponse<List<OutletResponse>> list(@AuthenticationPrincipal TenantPrincipal principal) {
        return ApiResponse.success("Outlet berhasil diambil.", outletService.listAccessible(principal));
    }
}
