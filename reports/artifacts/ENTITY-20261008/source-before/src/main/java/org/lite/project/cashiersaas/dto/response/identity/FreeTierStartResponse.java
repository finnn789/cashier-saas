package org.lite.project.cashiersaas.dto.response.identity;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FreeTierStartResponse {
    private UUID tenantId;
    private String planCode;
    private String businessType;
    private String tenantStatus;
}
