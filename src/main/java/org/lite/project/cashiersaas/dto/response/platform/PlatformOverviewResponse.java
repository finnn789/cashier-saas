package org.lite.project.cashiersaas.dto.response.platform;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Map;

@Getter
@Setter
public class PlatformOverviewResponse {
    private long tenantsTotal;
    /** Keys FREETIER, PRO, PREMIUM, TRIAL; a tenant counts once (TRIAL = running trial without a paid Premium). */
    private Map<String, Long> tenantsByPlan;
    private long suspendedTenants;
    private long issuedInvoices;
    private long paidThisMonthCount;
    private BigDecimal paidThisMonthAmount;
    private long expiringIn7Days;
}
