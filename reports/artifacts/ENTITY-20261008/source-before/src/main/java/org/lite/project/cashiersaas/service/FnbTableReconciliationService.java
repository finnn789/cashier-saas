package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.fnb.DiningTableEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.enums.PaymentStatus;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FnbTableReconciliationService {
    private final FnbOrderRepository orders;

    // The caller owns the tenant lock and transaction so payment, cancellation, and kitchen completion
    // serialize their view of every active order at the table.
    public void reconcile(SalesOrderEntity order) {
        DiningTableEntity table = order.getDiningTable();
        if (table == null) {
            return;
        }
        List<SalesOrderEntity> activeOrders = orders.activeForTable(order.getTenant().getId(),
                order.getOutlet().getId(), table.getId());
        boolean hasUnpaidOrder = activeOrders.stream()
                .anyMatch(activeOrder -> activeOrder.getPaymentStatus() != PaymentStatus.PAID);
        if (hasUnpaidOrder) {
            table.markAwaitingPayment();
        } else if (!activeOrders.isEmpty()) {
            table.markActive();
        } else {
            table.markVacant();
        }
    }
}
