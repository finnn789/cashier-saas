package org.lite.project.cashiersaas.dto.response;
import com.fasterxml.jackson.annotation.JsonInclude; import lombok.Getter; import lombok.Setter; import java.math.BigDecimal; import java.util.List;
@Getter @Setter @JsonInclude(JsonInclude.Include.NON_NULL)
public class FnbReceiptItemResponse { private String productName; private int quantity; private BigDecimal unitPrice; private BigDecimal lineTotal; private List<FnbReceiptOptionResponse> options; }
