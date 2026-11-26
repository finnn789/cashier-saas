package org.lite.project.cashiersaas.enums;

public enum BillingCycle {
    MONTHLY(1),
    YEARLY(12);

    private final int months;

    BillingCycle(int months) {
        this.months = months;
    }

    public int getMonths() {
        return months;
    }
}
