package org.lite.project.cashiersaas.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.FnbCashExpenseRequest;
import org.lite.project.cashiersaas.dto.request.FnbShiftCloseRequest;
import org.lite.project.cashiersaas.dto.request.FnbShiftOpenRequest;
import org.lite.project.cashiersaas.dto.response.FnbCashExpenseResponse;
import org.lite.project.cashiersaas.dto.response.FnbShiftOpeningSuggestionResponse;
import org.lite.project.cashiersaas.dto.response.FnbShiftPageResponse;
import org.lite.project.cashiersaas.dto.response.FnbShiftResponse;
import org.lite.project.cashiersaas.service.FnbShiftService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class FnbShiftController {
    private static final String CASHIER = "/api/v1/cashier/outlets/{outletId}/fnb/shifts";
    private static final String MERCHANT = "/api/v1/merchant/outlets/{outletId}/fnb/shifts";
    private final FnbShiftService shifts;

    @PostMapping(CASHIER)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FnbShiftResponse> open(@AuthenticationPrincipal TenantPrincipal principal,
                                              @PathVariable UUID outletId,
                                              @Valid @RequestBody FnbShiftOpenRequest request) {
        return ApiResponse.created("Shift dibuka.", shifts.open(principal, outletId, request));
    }

    @GetMapping(CASHIER + "/current")
    public ApiResponse<FnbShiftResponse> current(@AuthenticationPrincipal TenantPrincipal principal,
                                                 @PathVariable UUID outletId) {
        return ApiResponse.success("Shift aktif diambil.", shifts.current(principal, outletId));
    }

    @GetMapping(CASHIER + "/opening-suggestion")
    public ApiResponse<FnbShiftOpeningSuggestionResponse> openingSuggestion(@AuthenticationPrincipal TenantPrincipal principal,
                                                                          @PathVariable UUID outletId) {
        return ApiResponse.success("Saran modal awal diambil.", shifts.openingSuggestion(principal, outletId));
    }

    @PostMapping(CASHIER + "/current/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FnbCashExpenseResponse> recordExpense(@AuthenticationPrincipal TenantPrincipal principal,
                                                             @PathVariable UUID outletId,
                                                             @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                             @Valid @RequestBody FnbCashExpenseRequest request) {
        return ApiResponse.created("Pengeluaran dicatat.", shifts.recordExpense(principal, outletId, key, request));
    }

    @GetMapping(CASHIER + "/current/expenses")
    public ApiResponse<List<FnbCashExpenseResponse>> currentExpenses(@AuthenticationPrincipal TenantPrincipal principal,
                                                                    @PathVariable UUID outletId) {
        return ApiResponse.success("Pengeluaran shift diambil.", shifts.currentExpenses(principal, outletId));
    }

    @PostMapping(CASHIER + "/current/close")
    public ApiResponse<FnbShiftResponse> closeCurrent(@AuthenticationPrincipal TenantPrincipal principal,
                                                      @PathVariable UUID outletId,
                                                      @Valid @RequestBody FnbShiftCloseRequest request) {
        return ApiResponse.success("Shift ditutup.", shifts.closeCurrent(principal, outletId, request));
    }

    @GetMapping(MERCHANT)
    public ApiResponse<FnbShiftPageResponse> list(@AuthenticationPrincipal TenantPrincipal principal,
                                                  @PathVariable UUID outletId,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                  @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
                                                  @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return ApiResponse.success("Daftar shift diambil.", shifts.list(principal, outletId, from, to, page, size));
    }

    @GetMapping(MERCHANT + "/{shiftId}")
    public ApiResponse<FnbShiftResponse> detail(@AuthenticationPrincipal TenantPrincipal principal,
                                                @PathVariable UUID outletId, @PathVariable UUID shiftId) {
        return ApiResponse.success("Detail shift diambil.", shifts.detail(principal, outletId, shiftId));
    }

    @PostMapping(MERCHANT + "/{shiftId}/close")
    public ApiResponse<FnbShiftResponse> forceClose(@AuthenticationPrincipal TenantPrincipal principal,
                                                    @PathVariable UUID outletId, @PathVariable UUID shiftId,
                                                    @Valid @RequestBody FnbShiftCloseRequest request) {
        return ApiResponse.success("Shift ditutup oleh pemilik.", shifts.forceClose(principal, outletId, shiftId, request));
    }
}
