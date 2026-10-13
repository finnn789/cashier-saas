package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.enums.DiningTableStatus;
import java.util.UUID;

@Getter @Setter
public class FnbTableResponse {
    private UUID id;
    private String name;
    private DiningTableStatus status;
    private boolean active;
    private String qrToken;
}
