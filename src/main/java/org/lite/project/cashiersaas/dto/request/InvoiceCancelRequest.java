package org.lite.project.cashiersaas.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InvoiceCancelRequest {
    @Size(max = 500)
    private String reason;
}
