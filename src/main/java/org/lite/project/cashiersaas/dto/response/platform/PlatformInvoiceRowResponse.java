package org.lite.project.cashiersaas.dto.response.platform;

import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.dto.response.PlatformInvoiceResponse;

import java.util.UUID;

/** An invoice of the owner-facing shape plus the tenant it belongs to. */
@Getter
@Setter
public class PlatformInvoiceRowResponse extends PlatformInvoiceResponse {
    private UUID tenantId;
    private String tenantName;
}
