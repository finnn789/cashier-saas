package org.lite.project.cashiersaas.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.enums.Role;

import java.util.UUID;

@Getter
@RequiredArgsConstructor
public class MerchantOutletChoiceProjection {
    private final UUID tenantId;
    private final String businessName;
    private final UUID outletId;
    private final String outletName;
    private final Role role;
}
