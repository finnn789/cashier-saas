package org.lite.project.cashiersaas.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CashierAccountResponse {
    private UUID userId;
    private UUID outletId;
    private String email;
    private String fullName;
    private String role;
}
