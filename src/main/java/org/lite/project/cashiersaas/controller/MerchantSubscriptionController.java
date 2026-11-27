package org.lite.project.cashiersaas.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.InvoiceCancelRequest;
import org.lite.project.cashiersaas.dto.request.MockPaymentRequest;
import org.lite.project.cashiersaas.dto.request.SubscriptionCheckoutRequest;
import org.lite.project.cashiersaas.dto.response.MerchantSubscriptionResponse;
import org.lite.project.cashiersaas.dto.response.PlatformInvoiceResponse;
import org.lite.project.cashiersaas.dto.response.SubscriptionQuoteResponse;
import org.lite.project.cashiersaas.service.MerchantSubscriptionService;
import org.lite.project.cashiersaas.service.PlatformBillingService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/merchant/subscription")
public class MerchantSubscriptionController {
    private final MerchantSubscriptionService subscriptionService;
    private final PlatformBillingService billingService;

    @GetMapping
    public ApiResponse<MerchantSubscriptionResponse> get(@AuthenticationPrincipal TenantPrincipal principal) {
        return ApiResponse.success("Langganan diambil.", subscriptionService.get(principal));
    }

    @PostMapping("/quote")
    public ApiResponse<SubscriptionQuoteResponse> quote(@AuthenticationPrincipal TenantPrincipal principal,
                                                        @Valid @RequestBody SubscriptionCheckoutRequest request) {
        return ApiResponse.success("Rincian tagihan dihitung.", billingService.quote(principal, request));
    }

    @PostMapping("/checkout")
    public ApiResponse<PlatformInvoiceResponse> checkout(@AuthenticationPrincipal TenantPrincipal principal,
                                                         @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                         @Valid @RequestBody SubscriptionCheckoutRequest request) {
        return ApiResponse.success("Tagihan dibuat.", billingService.checkout(principal, key, request));
    }

    @GetMapping("/invoices")
    public ApiResponse<List<PlatformInvoiceResponse>> invoices(@AuthenticationPrincipal TenantPrincipal principal) {
        return ApiResponse.success("Tagihan diambil.", billingService.list(principal));
    }

    @GetMapping("/invoices/{invoiceId}")
    public ApiResponse<PlatformInvoiceResponse> invoice(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID invoiceId) {
        return ApiResponse.success("Tagihan diambil.", billingService.get(principal, invoiceId));
    }

    @PostMapping("/invoices/{invoiceId}/mock-pay")
    public ApiResponse<PlatformInvoiceResponse> mockPay(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID invoiceId,
                                                        @Valid @RequestBody MockPaymentRequest request) {
        return ApiResponse.success("Simulasi pembayaran diproses.", billingService.mockPay(principal, invoiceId, request));
    }

    @PostMapping("/invoices/{invoiceId}/pay")
    public ApiResponse<PlatformInvoiceResponse> pay(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID invoiceId) {
        return ApiResponse.success("Halaman pembayaran disiapkan.", billingService.pay(principal, invoiceId));
    }

    @PostMapping("/invoices/{invoiceId}/cancel")
    public ApiResponse<PlatformInvoiceResponse> cancel(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID invoiceId,
                                                       @Valid @RequestBody(required = false) InvoiceCancelRequest request) {
        return ApiResponse.success("Tagihan dibatalkan.", billingService.cancelForOwner(principal, invoiceId, request));
    }
}
