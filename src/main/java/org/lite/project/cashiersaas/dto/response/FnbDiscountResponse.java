package org.lite.project.cashiersaas.dto.response;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.enums.DiscountType;
import java.math.BigDecimal;
import java.util.UUID;
@Getter @Setter @JsonInclude(JsonInclude.Include.NON_NULL)
public class FnbDiscountResponse { private UUID orderId; private DiscountType type; private BigDecimal value; private BigDecimal amount; private BigDecimal total; private String reason; }
