package org.lite.project.cashiersaas.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import java.util.UUID;

@Getter @Setter
public class FnbCatalogCopyRequest {
    @NotNull private UUID sourceOutletId;
}
