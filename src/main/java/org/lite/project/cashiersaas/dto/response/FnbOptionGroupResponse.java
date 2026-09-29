package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;
import java.util.UUID;

@Getter @Setter
public class FnbOptionGroupResponse {
    private UUID id;
    private UUID productId;
    private String name;
    private boolean required;
    private int minimumSelections;
    private int maximumSelections;
    private int displayOrder;
}
