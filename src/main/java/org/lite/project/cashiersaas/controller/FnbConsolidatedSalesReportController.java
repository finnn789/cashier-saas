package org.lite.project.cashiersaas.controller;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.FnbSalesReportFilter;
import org.lite.project.cashiersaas.dto.response.FnbConsolidatedSalesReportResponse;
import org.lite.project.cashiersaas.service.FnbSalesReportService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/merchant/reports/sales")
public class FnbConsolidatedSalesReportController {
    private final FnbSalesReportService reports;

    @GetMapping
    public ApiResponse<FnbConsolidatedSalesReportResponse> consolidated(@AuthenticationPrincipal TenantPrincipal principal, @ModelAttribute FnbSalesReportFilter filter) {
        return ApiResponse.success("Laporan penjualan gabungan diambil.", reports.consolidated(principal, filter));
    }
}
