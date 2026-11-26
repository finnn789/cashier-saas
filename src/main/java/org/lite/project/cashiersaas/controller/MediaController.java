package org.lite.project.cashiersaas.controller;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.response.MediaUploadResponse;
import org.lite.project.cashiersaas.service.MediaStorageService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class MediaController {
    private final MediaStorageService service;

    @PostMapping(value = {"/merchant/outlets/{outletId}/media/images", "/cashier/outlets/{outletId}/media/images"},
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MediaUploadResponse> upload(@AuthenticationPrincipal TenantPrincipal principal,
                                                   @PathVariable UUID outletId,
                                                   @RequestPart("file") MultipartFile file) {
        return ApiResponse.created("Gambar diunggah.", service.storeImage(principal, outletId, file));
    }
}
