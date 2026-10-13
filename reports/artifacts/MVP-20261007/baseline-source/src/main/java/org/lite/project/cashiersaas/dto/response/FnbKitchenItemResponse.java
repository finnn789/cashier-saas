package org.lite.project.cashiersaas.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FnbKitchenItemResponse {
    private UUID itemId;
    private String productName;
    private int quantity;
    private String note;
    private List<FnbKitchenOptionResponse> options;
}
