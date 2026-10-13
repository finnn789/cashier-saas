package org.lite.project.cashiersaas.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.MerchantAuditFilter;
import org.lite.project.cashiersaas.dto.response.MerchantAuditPageResponse;
import org.lite.project.cashiersaas.service.MerchantAuditService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/merchant/outlets/{outletId}/audit-logs")
public class MerchantAuditController {
    private final MerchantAuditService audits;

    @GetMapping
    public ApiResponse<MerchantAuditPageResponse> list(@AuthenticationPrincipal TenantPrincipal principal,
                                                        @PathVariable UUID outletId,
                                                        @Valid @ModelAttribute MerchantAuditFilter filter) {
        return ApiResponse.success("Log audit diambil.", audits.list(principal, outletId, filter));
    }
}
