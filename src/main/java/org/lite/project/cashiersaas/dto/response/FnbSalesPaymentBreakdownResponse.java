package org.lite.project.cashiersaas.dto.response;
import com.fasterxml.jackson.annotation.JsonInclude; import lombok.Getter; import lombok.Setter; import org.lite.project.cashiersaas.enums.PaymentMethod; import java.math.BigDecimal;
@Getter @Setter @JsonInclude(JsonInclude.Include.NON_NULL)
public class FnbSalesPaymentBreakdownResponse { private PaymentMethod method; private String methodLabel; private long transactionCount; private BigDecimal netSales; private BigDecimal amountCollected; }
