package org.lite.project.cashiersaas.dto.request;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter
public class FnbCancelOrderRequest { @NotBlank @Size(max = 500) private String reason; }
