package org.lite.project.cashiersaas.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.dto.response.MembershipPlanResponse;
import org.lite.project.cashiersaas.service.MembershipPlanCatalogService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/public/plans")
@RequiredArgsConstructor
public class MembershipPlanCatalogController {

    private final MembershipPlanCatalogService membershipPlanCatalogService;

    @GetMapping
    @SecurityRequirements
    public ApiResponse<List<MembershipPlanResponse>> getAvailablePlans() {
        return ApiResponse.success("Katalog paket berhasil diambil.", membershipPlanCatalogService.getAvailablePlans()
        );
    }
}
