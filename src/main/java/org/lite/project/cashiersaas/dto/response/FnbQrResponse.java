package org.lite.project.cashiersaas.dto.response;
import lombok.Getter;
import lombok.Setter;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.lite.project.cashiersaas.enums.*;
@Getter @Setter @JsonInclude(JsonInclude.Include.NON_NULL)
public class FnbQrResponse {
    private String qrType;
    private String token;
    private String menuPath;
}
