package org.lite.project.cashiersaas.enums;

/** Subscription plans in rank order: a later constant includes everything of the earlier ones. */
public enum PlanCode {
    FREETIER("Freetier"),
    PRO("Pro"),
    PREMIUM("Premium");

    private final String displayName;

    PlanCode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
