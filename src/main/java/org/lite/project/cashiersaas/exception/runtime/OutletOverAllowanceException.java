package org.lite.project.cashiersaas.exception.runtime;

import lombok.Getter;
import org.lite.project.cashiersaas.service.PlanEntitlementService;

import java.util.List;
import java.util.UUID;

/** 402 for an active outlet beyond the plan's outlet allowance; carries the outlets the user can switch to. */
@Getter
public class OutletOverAllowanceException extends PlanUpgradeRequiredException {
    private final List<UUID> usableOutletIds;

    public OutletOverAllowanceException(List<UUID> usableOutletIds) {
        super(PlanEntitlementService.OUTLET_OVER_ALLOWANCE_MESSAGE);
        this.usableOutletIds = usableOutletIds;
    }
}
