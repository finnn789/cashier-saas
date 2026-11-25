package org.lite.project.cashiersaas.controller;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.response.FnbMenuMarginReportResponse;
import org.lite.project.cashiersaas.service.FnbMenuMarginService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/merchant")
public class FnbMenuMarginReportController {
    private final FnbMenuMarginService margins;

    @GetMapping("/outlets/{outletId}/fnb/reports/menu-margins")
    public ApiResponse<FnbMenuMarginReportResponse> report(@AuthenticationPrincipal TenantPrincipal principal,
                                                           @PathVariable UUID outletId,
                                                           @RequestParam(defaultValue = "false") boolean belowTargetOnly) {
        return ApiResponse.success("Margin menu diambil.", margins.menuMargins(principal, outletId, belowTargetOnly));
    }
}
