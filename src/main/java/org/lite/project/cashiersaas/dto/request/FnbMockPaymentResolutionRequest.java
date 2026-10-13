package org.lite.project.cashiersaas.dto.request;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.lite.project.cashiersaas.enums.FnbGatewayOutcome;
@Getter @Setter
public class FnbMockPaymentResolutionRequest { @NotNull private FnbGatewayOutcome outcome; }
