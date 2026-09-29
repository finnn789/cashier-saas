package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;
import java.util.UUID;

@Getter @Setter
public class FnbCategoryResponse {
    private UUID id;
    private String name;
    private int displayOrder;
    private boolean archived;
}
