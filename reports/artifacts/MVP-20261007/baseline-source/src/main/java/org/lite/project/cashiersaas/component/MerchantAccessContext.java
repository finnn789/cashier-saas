package org.lite.project.cashiersaas.component;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;

@Getter
@RequiredArgsConstructor
public class MerchantAccessContext {
    private final TenantEntity tenant;
    private final OutletEntity outlet;
    private final TenantMembershipEntity membership;
}
