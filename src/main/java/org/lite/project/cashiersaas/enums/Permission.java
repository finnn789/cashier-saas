package org.lite.project.cashiersaas.enums;

import java.util.Set;

/** Fixed role-to-permission matrix; ORDER_DISCOUNT for a cashier is additionally capped by the owner. */
public enum Permission {
    CATALOG_EDIT(Role.OWNERMERCHANT, Role.CASHIER),
    CATALOG_PRICE(Role.OWNERMERCHANT),
    INVENTORY_ITEM_CREATE(Role.OWNERMERCHANT, Role.CASHIER),
    STOCK_MOVE(Role.OWNERMERCHANT, Role.CASHIER),
    STOCK_ADJUST(Role.OWNERMERCHANT),
    PURCHASE_RECORD(Role.OWNERMERCHANT, Role.CASHIER),
    ORDER_DISCOUNT(Role.OWNERMERCHANT, Role.CASHIER);

    private final Set<Role> roles;

    Permission(Role... roles) {
        this.roles = Set.of(roles);
    }

    public boolean allows(Role role) {
        return roles.contains(role);
    }
}
