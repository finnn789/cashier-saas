package org.lite.project.cashiersaas.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FnbKitchenPageResponse {
    private List<FnbKitchenOrderResponse> orders;
    private int page;
    private int size;
    private long total;
}
