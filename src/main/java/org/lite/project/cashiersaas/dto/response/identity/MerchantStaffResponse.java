package org.lite.project.cashiersaas.dto.response.identity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class MerchantStaffResponse {
    private UUID userId;
    private String fullName;
    private String email;
    private String phoneNumber;
    private boolean active;
    private Instant joinedAt;
}
