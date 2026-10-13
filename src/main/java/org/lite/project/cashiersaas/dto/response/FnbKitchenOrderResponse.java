package org.lite.project.cashiersaas.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.enums.OrderSource;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.ServiceType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FnbKitchenOrderResponse {
    private UUID orderId;
    private String orderNumber;
    private OrderSource source;
    private ServiceType serviceType;
    private UUID tableId;
    private String tableName;
    private String customerLabel;
    private OrderStatus status;
    private Instant createdAt;
    private List<FnbKitchenItemResponse> items;
}
