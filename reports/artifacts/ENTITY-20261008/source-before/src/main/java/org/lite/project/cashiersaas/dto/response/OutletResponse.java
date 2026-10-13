package org.lite.project.cashiersaas.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OutletResponse {
    private UUID id;
    private String name;
    private String timeZone;
    private String address;
    private String phoneNumber;
}
