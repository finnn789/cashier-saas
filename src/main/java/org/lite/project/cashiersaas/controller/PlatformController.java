package org.lite.project.cashiersaas.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.PlatformPrincipal;
import org.lite.project.cashiersaas.dto.request.platform.PlatformAiTopupRequest;
import org.lite.project.cashiersaas.dto.request.platform.PlatformGrantPlanRequest;
import org.lite.project.cashiersaas.dto.request.platform.PlatformMarkPaidRequest;
import org.lite.project.cashiersaas.dto.request.platform.PlatformReasonRequest;
import org.lite.project.cashiersaas.dto.response.platform.PlatformAuditItemResponse;
import org.lite.project.cashiersaas.dto.response.platform.PlatformInvoiceRowResponse;
import org.lite.project.cashiersaas.dto.response.platform.PlatformOverviewResponse;
import org.lite.project.cashiersaas.dto.response.platform.PlatformPageResponse;
import org.lite.project.cashiersaas.dto.response.platform.PlatformTenantDetailResponse;
import org.lite.project.cashiersaas.dto.response.platform.PlatformTenantItemResponse;
import org.lite.project.cashiersaas.service.PlatformDashboardService;
import org.lite.project.cashiersaas.service.PlatformTenantAdminService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** SUPERADMIN panel. SecurityConfiguration admits only the ROLE_SUPERADMIN of a PLATFORM token; the services re-check the admin flag. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/platform")
public class PlatformController {
    private final PlatformDashboardService dashboard;
    private final PlatformTenantAdminService tenantAdmin;

    @GetMapping("/overview")
    public ApiResponse<PlatformOverviewResponse> overview(@AuthenticationPrincipal PlatformPrincipal principal) {
        return ApiResponse.success("Ringkasan platform diambil.", dashboard.overview(principal));
    }

    @GetMapping("/tenants")
    public ApiResponse<PlatformPageResponse<PlatformTenantItemResponse>> tenants(
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String plan,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success("Daftar tenant diambil.", dashboard.tenants(principal, q, plan, status, page, size));
    }

    @GetMapping("/tenants/{tenantId}")
    public ApiResponse<PlatformTenantDetailResponse> tenant(@AuthenticationPrincipal PlatformPrincipal principal,
                                                            @PathVariable UUID tenantId) {
        return ApiResponse.success("Detail tenant diambil.", dashboard.tenant(principal, tenantId));
    }

    @PostMapping("/tenants/{tenantId}/subscriptions")
    public ApiResponse<PlatformTenantDetailResponse> grantPlan(@AuthenticationPrincipal PlatformPrincipal principal,
                                                               @PathVariable UUID tenantId,
                                                               @Valid @RequestBody PlatformGrantPlanRequest request) {
        return ApiResponse.success("Paket diberikan.", tenantAdmin.grantPlan(principal, tenantId, request));
    }

    @PostMapping("/tenants/{tenantId}/subscriptions/{subscriptionId}/end")
    public ApiResponse<PlatformTenantDetailResponse> endPlan(@AuthenticationPrincipal PlatformPrincipal principal,
                                                             @PathVariable UUID tenantId, @PathVariable UUID subscriptionId,
                                                             @Valid @RequestBody PlatformReasonRequest request) {
        return ApiResponse.success("Paket diakhiri.", tenantAdmin.endPlan(principal, tenantId, subscriptionId, request));
    }

    @PostMapping("/tenants/{tenantId}/ai-topup")
    public ApiResponse<PlatformTenantDetailResponse> aiTopUp(@AuthenticationPrincipal PlatformPrincipal principal,
                                                             @PathVariable UUID tenantId,
                                                             @Valid @RequestBody PlatformAiTopupRequest request) {
        return ApiResponse.success("Kuota AI ditambahkan.", tenantAdmin.topUpAi(principal, tenantId, request));
    }

    @PostMapping("/tenants/{tenantId}/suspend")
    public ApiResponse<PlatformTenantDetailResponse> suspend(@AuthenticationPrincipal PlatformPrincipal principal,
                                                             @PathVariable UUID tenantId,
                                                             @Valid @RequestBody PlatformReasonRequest request) {
        return ApiResponse.success("Tenant ditangguhkan.", tenantAdmin.suspend(principal, tenantId, request));
    }

    @PostMapping("/tenants/{tenantId}/reactivate")
    public ApiResponse<PlatformTenantDetailResponse> reactivate(@AuthenticationPrincipal PlatformPrincipal principal,
                                                                @PathVariable UUID tenantId,
                                                                @Valid @RequestBody PlatformReasonRequest request) {
        return ApiResponse.success("Tenant diaktifkan kembali.", tenantAdmin.reactivate(principal, tenantId, request));
    }

    @GetMapping("/invoices")
    public ApiResponse<PlatformPageResponse<PlatformInvoiceRowResponse>> invoices(
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success("Daftar tagihan diambil.", dashboard.invoices(principal, status, q, page, size));
    }

    @PostMapping("/invoices/{invoiceId}/mark-paid")
    public ApiResponse<PlatformInvoiceRowResponse> markPaid(@AuthenticationPrincipal PlatformPrincipal principal,
                                                            @PathVariable UUID invoiceId,
                                                            @Valid @RequestBody PlatformMarkPaidRequest request) {
        return ApiResponse.success("Tagihan ditandai lunas.", tenantAdmin.markPaid(principal, invoiceId, request));
    }

    @PostMapping("/invoices/{invoiceId}/cancel")
    public ApiResponse<PlatformInvoiceRowResponse> cancelInvoice(@AuthenticationPrincipal PlatformPrincipal principal,
                                                                 @PathVariable UUID invoiceId,
                                                                 @Valid @RequestBody PlatformReasonRequest request) {
        return ApiResponse.success("Tagihan dibatalkan.", tenantAdmin.cancelInvoice(principal, invoiceId, request));
    }

    @GetMapping("/audit")
    public ApiResponse<PlatformPageResponse<PlatformAuditItemResponse>> audit(
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success("Audit platform diambil.", dashboard.audit(principal, tenantId, page, size));
    }
}
