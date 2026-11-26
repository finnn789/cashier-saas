package org.lite.project.cashiersaas.dto.response.platform;

import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.dto.response.PlatformInvoiceResponse;

import java.util.List;

@Getter
@Setter
public class PlatformTenantDetailResponse extends PlatformTenantItemResponse {
    /** Newest first. */
    private List<PlatformSubscriptionResponse> subscriptions;
    /** Last 20. */
    private List<PlatformInvoiceResponse> invoices;
    private PlatformAiUsageResponse ai;
}
