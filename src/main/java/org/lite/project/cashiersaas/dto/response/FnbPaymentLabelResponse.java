package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class FnbPaymentLabelResponse {
    private UUID id;
    private String name;
    private boolean active;
    private int displayOrder;
}
