package org.lite.project.cashiersaas.controller;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.service.MidtransWebhookService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public endpoint: trust comes from the signature check and the status re-read in the service, not from authentication. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/webhooks/midtrans")
public class MidtransWebhookController {
    private final MidtransWebhookService webhook;

    @PostMapping
    public ApiResponse<Object> notification(@RequestBody String rawBody) {
        webhook.handle(rawBody);
        return ApiResponse.success("Notifikasi diterima.", null);
    }
}
