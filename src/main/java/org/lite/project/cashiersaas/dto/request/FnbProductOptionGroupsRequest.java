package org.lite.project.cashiersaas.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import java.util.List;
import java.util.UUID;

@Getter @Setter
public class FnbProductOptionGroupsRequest {
    @NotNull @Size(max = 20) private List<UUID> groupIds;
}
