package org.lite.project.cashiersaas.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.FnbCatalogCopyRequest;
import org.lite.project.cashiersaas.dto.response.FnbCatalogCopyResponse;
import org.lite.project.cashiersaas.service.FnbCatalogCopyService;
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
@RequestMapping("/api/v1/merchant/outlets")
public class FnbCatalogCopyController {
    private final FnbCatalogCopyService service;

    @PostMapping("/{targetOutletId}/catalog/copy")
    public ApiResponse<FnbCatalogCopyResponse> copy(@AuthenticationPrincipal TenantPrincipal principal,
                                                    @PathVariable UUID targetOutletId,
                                                    @Valid @RequestBody FnbCatalogCopyRequest request) {
        return ApiResponse.success("Menu berhasil disalin.", service.copy(principal, targetOutletId, request));
    }
}
