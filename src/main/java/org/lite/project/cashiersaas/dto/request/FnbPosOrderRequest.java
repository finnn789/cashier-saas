package org.lite.project.cashiersaas.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.enums.ServiceType;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class FnbPosOrderRequest {
    @NotNull private ServiceType serviceType;
    private UUID tableId;
    @NotBlank @Size(max = 80) private String customerLabel;
    @NotEmpty @Size(max = 50) private List<@NotNull @Valid Item> items;

    @Getter
    @Setter
    public static class Item {
        @NotNull private UUID productId;
        @Min(1) @Max(100) private int quantity;
        @Size(max = 500) private String note;
        @NotNull @Size(max = 100) private List<@NotNull UUID> optionIds;
    }
}
