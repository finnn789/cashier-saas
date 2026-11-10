package org.lite.project.cashiersaas.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.*;
import org.lite.project.cashiersaas.dto.response.*;
import org.lite.project.cashiersaas.service.FnbCatalogService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class FnbCatalogController {
    private final FnbCatalogService service;

    @PostMapping("/merchant/outlets/{outletId}/fnb/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FnbCategoryResponse> createCategory(@AuthenticationPrincipal TenantPrincipal principal,
                                                           @PathVariable UUID outletId,
                                                           @Valid @RequestBody FnbCategoryRequest request) {
        return ApiResponse.created("Kategori dibuat.", service.createCategory(principal, outletId, request));
    }

    @PutMapping("/merchant/outlets/{outletId}/fnb/categories/{id}")
    public ApiResponse<FnbCategoryResponse> updateCategory(@AuthenticationPrincipal TenantPrincipal principal,
                                                           @PathVariable UUID outletId,
                                                           @PathVariable UUID id,
                                                           @Valid @RequestBody FnbCategoryRequest request) {
        return ApiResponse.success("Kategori diperbarui.", service.updateCategory(principal, outletId, id, request));
    }

    @DeleteMapping("/merchant/outlets/{outletId}/fnb/categories/{id}")
    public ApiResponse<FnbCategoryResponse> archiveCategory(@AuthenticationPrincipal TenantPrincipal principal,
                                                            @PathVariable UUID outletId,
                                                            @PathVariable UUID id) {
        return ApiResponse.success("Kategori diarsipkan.", service.archiveCategory(principal, outletId, id));
    }

    @GetMapping("/cashier/outlets/{outletId}/fnb/categories")
    public ApiResponse<List<FnbCategoryResponse>> categories(@AuthenticationPrincipal TenantPrincipal principal,
                                                             @PathVariable UUID outletId) {
        return ApiResponse.success("Kategori diambil.", service.categories(principal, outletId));
    }

    @PostMapping("/merchant/outlets/{outletId}/fnb/products")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FnbProductResponse> createProduct(@AuthenticationPrincipal TenantPrincipal principal,
                                                         @PathVariable UUID outletId,
                                                         @Valid @RequestBody FnbProductRequest request) {
        return ApiResponse.created("Produk dibuat.", service.createProduct(principal, outletId, request));
    }

    @PutMapping("/merchant/outlets/{outletId}/fnb/products/{id}")
    public ApiResponse<FnbProductResponse> updateProduct(@AuthenticationPrincipal TenantPrincipal principal,
                                                         @PathVariable UUID outletId,
                                                         @PathVariable UUID id,
                                                         @Valid @RequestBody FnbProductRequest request) {
        return ApiResponse.success("Produk diperbarui.", service.updateProduct(principal, outletId, id, request));
    }

    @DeleteMapping("/merchant/outlets/{outletId}/fnb/products/{id}")
    public ApiResponse<FnbProductResponse> archiveProduct(@AuthenticationPrincipal TenantPrincipal principal,
                                                          @PathVariable UUID outletId,

                                                          @PathVariable UUID id) {
        return ApiResponse.success("Produk diarsipkan.", service.archiveProduct(principal, outletId, id));
    }

    @GetMapping("/cashier/outlets/{outletId}/fnb/products")
    public ApiResponse<List<FnbProductResponse>> products(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId) {
        return ApiResponse.success("Produk diambil.", service.products(principal, outletId));
    }

    @PostMapping("/merchant/outlets/{outletId}/fnb/option-groups")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FnbOptionGroupResponse> createGroup(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId, @Valid @RequestBody FnbOptionGroupRequest request) {
        return ApiResponse.created("Grup pilihan dibuat.", service.createGroup(principal, outletId, request));
    }

    @PutMapping("/merchant/outlets/{outletId}/fnb/option-groups/{id}")
    public ApiResponse<FnbOptionGroupResponse> updateGroup(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId, @PathVariable UUID id, @Valid @RequestBody FnbOptionGroupRequest request) {
        return ApiResponse.success("Grup pilihan diperbarui.", service.updateGroup(principal, outletId, id, request));
    }

    @DeleteMapping("/merchant/outlets/{outletId}/fnb/option-groups/{id}")
    public ApiResponse<FnbOptionGroupResponse> archiveGroup(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId, @PathVariable UUID id) {
        return ApiResponse.success("Grup pilihan diarsipkan.", service.archiveGroup(principal, outletId, id));
    }

    @GetMapping("/cashier/outlets/{outletId}/fnb/option-groups")
    public ApiResponse<List<FnbOptionGroupResponse>> groups(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId) {
        return ApiResponse.success("Grup pilihan diambil.", service.groups(principal, outletId));
    }

    @PutMapping("/merchant/outlets/{outletId}/fnb/products/{productId}/option-groups")
    public ApiResponse<List<FnbOptionGroupResponse>> replaceProductGroups(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId, @PathVariable UUID productId, @Valid @RequestBody FnbProductOptionGroupsRequest request) {
        return ApiResponse.success("Grup pilihan produk diperbarui.", service.replaceProductGroups(principal, outletId, productId, request));
    }

    @GetMapping("/cashier/outlets/{outletId}/fnb/products/{productId}/option-groups")
    public ApiResponse<List<FnbOptionGroupResponse>> productGroups(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId, @PathVariable UUID productId) {
        return ApiResponse.success("Grup pilihan produk diambil.", service.productGroups(principal, outletId, productId));
    }

    @PostMapping("/merchant/outlets/{outletId}/fnb/option-groups/{groupId}/options")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FnbOptionResponse> createOption(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId, @PathVariable UUID groupId, @Valid @RequestBody FnbOptionRequest request) {
        return ApiResponse.created("Pilihan dibuat.", service.createOption(principal, outletId, groupId, request));
    }

    @PutMapping("/merchant/outlets/{outletId}/fnb/option-groups/{groupId}/options/{id}")
    public ApiResponse<FnbOptionResponse> updateOption(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId, @PathVariable UUID groupId, @PathVariable UUID id, @Valid @RequestBody FnbOptionRequest request) {
        return ApiResponse.success("Pilihan diperbarui.", service.updateOption(principal, outletId, groupId, id, request));
    }

    @GetMapping("/cashier/outlets/{outletId}/fnb/option-groups/{groupId}/options")
    public ApiResponse<List<FnbOptionResponse>> options(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId, @PathVariable UUID groupId) {
        return ApiResponse.success("Pilihan diambil.", service.options(principal, outletId, groupId));
    }

    @PutMapping("/merchant/outlets/{outletId}/fnb/products/{productId}/recipe")
    public ApiResponse<FnbRecipeResponse> replaceProductRecipe(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId, @PathVariable UUID productId, @Valid @RequestBody FnbRecipeRequest request) {
        return ApiResponse.success("Resep produk diperbarui.", service.replaceProductRecipe(principal, outletId, productId, request));
    }

    @GetMapping("/merchant/outlets/{outletId}/fnb/products/{productId}/recipe")
    public ApiResponse<FnbRecipeResponse> productRecipe(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId, @PathVariable UUID productId) {
        return ApiResponse.success("Resep produk diambil.", service.productRecipe(principal, outletId, productId));
    }

    @PutMapping("/merchant/outlets/{outletId}/fnb/option-groups/{groupId}/options/{id}/recipe")
    public ApiResponse<FnbRecipeResponse> replaceOptionRecipe(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId, @PathVariable UUID groupId, @PathVariable UUID id, @Valid @RequestBody FnbRecipeRequest request) {
        return ApiResponse.success("Resep pilihan diperbarui.", service.replaceOptionRecipe(principal, outletId, groupId, id, request));
    }

    @GetMapping("/merchant/outlets/{outletId}/fnb/option-groups/{groupId}/options/{id}/recipe")
    public ApiResponse<FnbRecipeResponse> optionRecipe(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId, @PathVariable UUID groupId, @PathVariable UUID id) {
        return ApiResponse.success("Resep pilihan diambil.", service.optionRecipe(principal, outletId, groupId, id));
    }
}
