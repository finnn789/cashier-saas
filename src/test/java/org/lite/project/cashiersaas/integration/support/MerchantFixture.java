package org.lite.project.cashiersaas.integration.support;

import java.util.UUID;

/** An onboarded merchant: tenant, outlet and an OWNERMERCHANT bearer token obtained via the real onboarding flow. */
public final class MerchantFixture {
    private final UUID tenantId;
    private final UUID outletId;
    private final String bearer;

    public MerchantFixture(UUID tenantId, UUID outletId, String accessToken) {
        this.tenantId = tenantId;
        this.outletId = outletId;
        this.bearer = "Bearer " + accessToken;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID outletId() {
        return outletId;
    }

    public String bearer() {
        return bearer;
    }
}
