package org.lite.project.cashiersaas.controller;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.response.FnbLeakageConsolidatedResponse;
import org.lite.project.cashiersaas.dto.response.FnbLeakageReportResponse;
import org.lite.project.cashiersaas.dto.response.FnbStockCountSummaryResponse;
import org.lite.project.cashiersaas.service.FnbLeakageService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/merchant")
public class FnbLeakageReportController {
    private final FnbLeakageService leakage;

    @GetMapping("/outlets/{outletId}/fnb/inventory/stock-counts")
    public ApiResponse<List<FnbStockCountSummaryResponse>> stockCounts(@AuthenticationPrincipal TenantPrincipal principal,
                                                                       @PathVariable UUID outletId,
                                                                       @RequestParam(defaultValue = "0") int page,
                                                                       @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success("Riwayat stok opname diambil.", leakage.stockCounts(principal, outletId, page, size));
    }

    @GetMapping("/outlets/{outletId}/fnb/reports/leakage")
    public ApiResponse<FnbLeakageReportResponse> report(@AuthenticationPrincipal TenantPrincipal principal,
                                                        @PathVariable UUID outletId,
                                                        @RequestParam(required = false) UUID stockCountId) {
        return ApiResponse.success("Laporan kebocoran bahan diambil.", leakage.report(principal, outletId, stockCountId));
    }

    @GetMapping("/reports/leakage")
    public ApiResponse<FnbLeakageConsolidatedResponse> consolidated(@AuthenticationPrincipal TenantPrincipal principal,
                                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success("Laporan kebocoran bahan gabungan diambil.", leakage.consolidated(principal, from, to));
    }
}
