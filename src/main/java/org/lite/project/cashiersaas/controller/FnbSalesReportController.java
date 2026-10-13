package org.lite.project.cashiersaas.controller;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.FnbSalesReportFilter;
import org.lite.project.cashiersaas.dto.response.*;
import org.lite.project.cashiersaas.service.FnbSalesReportService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/merchant/outlets/{outletId}/fnb/reports/sales")
public class FnbSalesReportController {
    private final FnbSalesReportService reports;

    @GetMapping
    public ApiResponse<FnbSalesReportResponse> report(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId, @ModelAttribute FnbSalesReportFilter filter) {
        return ApiResponse.success("Laporan penjualan diambil.", reports.report(principal, outletId, filter));
    }

    @GetMapping("/csv")
    public ApiResponse<FnbSalesCsvResponse> csv(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId, @ModelAttribute FnbSalesReportFilter filter) {
        return ApiResponse.success("Ekspor CSV penjualan diambil.", reports.csv(principal, outletId, filter));
    }
}
