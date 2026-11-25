package org.lite.project.cashiersaas.controller;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.response.MerchantSubscriptionResponse;
import org.lite.project.cashiersaas.service.MerchantSubscriptionService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/merchant/subscription")
public class MerchantSubscriptionController {
    private final MerchantSubscriptionService subscriptionService;

    @GetMapping
    public ApiResponse<MerchantSubscriptionResponse> get(@AuthenticationPrincipal TenantPrincipal principal) {
        return ApiResponse.success("Langganan diambil.", subscriptionService.get(principal));
    }
}
