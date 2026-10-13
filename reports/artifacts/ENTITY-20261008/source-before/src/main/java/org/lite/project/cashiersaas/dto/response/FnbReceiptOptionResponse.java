package org.lite.project.cashiersaas.dto.response;
import com.fasterxml.jackson.annotation.JsonInclude; import lombok.Getter; import lombok.Setter; import java.math.BigDecimal;
@Getter @Setter @JsonInclude(JsonInclude.Include.NON_NULL)
public class FnbReceiptOptionResponse { private String name; private BigDecimal priceAdjustment; }
