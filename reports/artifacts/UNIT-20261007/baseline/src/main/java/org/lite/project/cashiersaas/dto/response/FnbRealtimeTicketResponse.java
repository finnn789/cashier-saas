package org.lite.project.cashiersaas.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FnbRealtimeTicketResponse {
    private String ticket;
    private Instant expiresAt;
    private String websocketPath;
    private String protocol;
}
