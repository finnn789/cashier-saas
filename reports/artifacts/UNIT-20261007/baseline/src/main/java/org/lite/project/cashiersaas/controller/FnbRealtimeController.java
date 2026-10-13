package org.lite.project.cashiersaas.controller;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.response.FnbRealtimeTicketResponse;
import org.lite.project.cashiersaas.service.FnbRealtimeTicketService;
import org.lite.project.cashiersaas.service.MerchantAccessGuard;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/cashier/outlets/{outletId}/fnb/realtime-ticket")
public class FnbRealtimeController {
    private final MerchantAccessGuard guard;
    private final FnbRealtimeTicketService tickets;
    @PostMapping
    public ApiResponse<FnbRealtimeTicketResponse> ticket(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID outletId,
                                                          @RequestHeader("Authorization") String authorization) {
        guard.requireOutlet(principal, outletId);
        return ApiResponse.success("Tiket realtime dibuat.", tickets.issue(principal, authorization));
    }
}
