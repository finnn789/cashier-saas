package org.lite.project.cashiersaas.exception.runtime;

import org.lite.project.cashiersaas.enums.PlanCode;

public class PlanUpgradeRequiredException extends RuntimeException {
    public PlanUpgradeRequiredException(PlanCode requiredPlan) {
        super("Fitur ini tersedia di paket " + requiredPlan.getDisplayName() + ".");
    }
}
