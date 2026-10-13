package org.lite.project.cashiersaas.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.enums.OrderSource;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentStatus;
import org.lite.project.cashiersaas.enums.ServiceType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FnbStaffOrderResponse {
    private UUID orderId;
    private String orderNumber;
    private OrderSource source;
    private ServiceType serviceType;
    private UUID tableId;
    private String tableName;
    private String customerLabel;
    private OrderStatus status;
    private PaymentStatus paymentStatus;
    private Instant createdAt;
    private BigDecimal subtotal;
    private BigDecimal taxRate;
    private BigDecimal taxAmount;
    private BigDecimal serviceChargeRate;
    private BigDecimal serviceChargeAmount;
    private BigDecimal discountAmount;
    private BigDecimal total;
    private List<FnbStaffOrderItemResponse> items;
}
