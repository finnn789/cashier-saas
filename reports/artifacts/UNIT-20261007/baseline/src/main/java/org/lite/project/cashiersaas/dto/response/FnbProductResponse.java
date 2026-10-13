package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.UUID;

@Getter @Setter
public class FnbProductResponse {
    private UUID id;
    private UUID categoryId;
    private String name;
    private String description;
    private BigDecimal price;
    private String imageUrl;
    private boolean available;
    private boolean archived;
    private int displayOrder;
}
