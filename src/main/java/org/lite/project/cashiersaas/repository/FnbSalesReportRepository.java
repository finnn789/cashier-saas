package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilder;
import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.fnb.CashShiftEntity;
import org.lite.project.cashiersaas.entity.fnb.OrderItemEntity;
import org.lite.project.cashiersaas.entity.fnb.PaymentEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentMethod;
import org.lite.project.cashiersaas.enums.PaymentStatus;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class FnbSalesReportRepository {

    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    public List<TransactionRow> transactions(UUID tenantId, UUID outletId, Instant start, Instant end,
                                             OrderStatus status, PaymentMethod method, int page, int size) {
        return paidSalesQuery(tenantId, outletId, start, end, status, method)
                .selectNew(TransactionRow.class)
                .with("salesOrder.id")
                .with("salesOrder.orderNumber")
                .with("salesOrder.status")
                .with("payment.method")
                .with("payment.methodLabel")
                .with("salesOrder.subtotal")
                .with("salesOrder.total")
                .with("payment.amountPaid")
                .with("payment.changeAmount")
                .with("salesOrder.discountAmount")
                .with("salesOrder.taxAmount")
                .with("salesOrder.serviceChargeAmount")
                .with("payment.receiptNumber")
                .with("payment.paidAt")
                .end()
                .orderByDesc("payment.paidAt")
                .orderByDesc("payment.id")
                .setFirstResult(page * size)
                .setMaxResults(size + 1)
                .getResultList();
    }

    public SummaryRow summary(UUID tenantId, UUID outletId, Instant start, Instant end,
                              OrderStatus status, PaymentMethod method) {
        return paidSalesQuery(tenantId, outletId, start, end, status, method)
                .selectNew(SummaryRow.class)
                .with("COALESCE(SUM(salesOrder.subtotal), 0)")
                .with("COALESCE(SUM(salesOrder.subtotal - salesOrder.discountAmount), 0)")
                .with("COALESCE(SUM(salesOrder.total), 0)")
                .with("COALESCE(SUM(payment.amountPaid - payment.changeAmount), 0)")
                .with("COUNT(payment.id)")
                .with("COALESCE(SUM(salesOrder.discountAmount), 0)")
                .with("COALESCE(SUM(salesOrder.taxAmount), 0)")
                .with("COALESCE(SUM(salesOrder.serviceChargeAmount), 0)")
                .end()
                .getSingleResult();
    }

    public long cancellations(UUID tenantId, UUID outletId, Instant start, Instant end) {
        return criteriaBuilderFactory.create(entityManager, SalesOrderEntity.class, "salesOrder")
                .where("salesOrder.tenant.id").eq(tenantId)
                .where("salesOrder.outlet.id").eq(outletId)
                .where("salesOrder.status").eq(OrderStatus.CANCELLED)
                .where("salesOrder.updatedAt").ge(start)
                .where("salesOrder.updatedAt").lt(end)
                .getCountQuery().getSingleResult();
    }

    public List<ProductRow> products(UUID tenantId, UUID outletId, Instant start, Instant end,
                                     OrderStatus status, PaymentMethod method, int limit) {
        CriteriaBuilder<Tuple> query = paidSalesQuery(tenantId, outletId, start, end, status, method)
                .innerJoinOn(OrderItemEntity.class, "item")
                .on("item.salesOrder.id").eqExpression("salesOrder.id")
                .on("item.tenant.id").eq(tenantId)
                .on("item.outlet.id").eq(outletId)
                .end();
        return query.selectNew(ProductRow.class)
                .with("item.product.id")
                .with("item.productNameSnapshot")
                .with("SUM(item.quantity)")
                .with("COALESCE(SUM(item.lineTotal), 0)")
                .with("COALESCE(SUM(item.costAmount), 0)")
                .with("COUNT(CASE WHEN item.costAmount IS NULL THEN 1 END)")
                .end()
                .groupBy("item.product.id")
                .groupBy("item.productNameSnapshot")
                .orderByDesc("SUM(item.lineTotal)")
                .orderByAsc("item.productNameSnapshot")
                .setMaxResults(limit)
                .getResultList();
    }

    public List<MethodRow> methods(UUID tenantId, UUID outletId, Instant start, Instant end,
                                   OrderStatus status, PaymentMethod method) {
        return paidSalesQuery(tenantId, outletId, start, end, status, method)
                .selectNew(MethodRow.class)
                .with("payment.method")
                .with("payment.methodLabel")
                .with("COUNT(payment.id)")
                .with("COALESCE(SUM(salesOrder.subtotal - salesOrder.discountAmount), 0)")
                .with("COALESCE(SUM(payment.amountPaid - payment.changeAmount), 0)")
                .end()
                .groupBy("payment.method")
                .groupBy("payment.methodLabel")
                .orderByAsc("payment.method")
                .orderByAsc("payment.methodLabel")
                .getResultList();
    }

    public CostRow cost(UUID tenantId, UUID outletId, Instant start, Instant end,
                        OrderStatus status, PaymentMethod method) {
        return paidSalesQuery(tenantId, outletId, start, end, status, method)
                .innerJoinOn(OrderItemEntity.class, "item")
                .on("item.salesOrder.id").eqExpression("salesOrder.id")
                .on("item.tenant.id").eq(tenantId)
                .on("item.outlet.id").eq(outletId)
                .end()
                .selectNew(CostRow.class)
                .with("COALESCE(SUM(item.costAmount), 0)")
                .with("COUNT(CASE WHEN item.costAmount IS NULL THEN 1 END)")
                .end()
                .getSingleResult();
    }

    /** Native only for the zone-aware hour grouping; applies the same filters as paidSalesQuery. */
    public List<HourlyRow> hourly(UUID tenantId, UUID outletId, Instant start, Instant end, String zone,
                                  OrderStatus status, PaymentMethod method) {
        StringBuilder sql = new StringBuilder("SELECT CAST(extract(hour FROM (p.paid_at AT TIME ZONE :zone)) AS integer) AS h, "
                + "COUNT(p.id), COALESCE(SUM(o.subtotal - o.discount_amount), 0) "
                + "FROM payments p JOIN sales_orders o ON o.id = p.sales_order_id "
                + "WHERE p.tenant_id = :tenantId AND p.outlet_id = :outletId AND o.tenant_id = :tenantId AND o.outlet_id = :outletId "
                + "AND p.paid_at >= :start AND p.paid_at < :end AND o.payment_status = 'PAID' AND o.status <> 'CANCELLED'");
        if (status != null) sql.append(" AND o.status = :status");
        if (method != null) sql.append(" AND p.method = :method");
        sql.append(" GROUP BY h ORDER BY h");
        Query nativeQuery = entityManager.createNativeQuery(sql.toString())
                .setParameter("zone", zone).setParameter("tenantId", tenantId).setParameter("outletId", outletId)
                .setParameter("start", start).setParameter("end", end);
        if (status != null) nativeQuery.setParameter("status", status.name());
        if (method != null) nativeQuery.setParameter("method", method.name());
        List<HourlyRow> rows = new ArrayList<>();
        for (Object result : nativeQuery.getResultList()) {
            Object[] columns = (Object[]) result;
            rows.add(new HourlyRow(((Number) columns[0]).intValue(), ((Number) columns[1]).longValue(),
                    new BigDecimal(columns[2].toString())));
        }
        return rows;
    }

    public List<CashierRow> cashiers(UUID tenantId, UUID outletId, Instant start, Instant end,
                                     OrderStatus status, PaymentMethod method) {
        return paidSalesQuery(tenantId, outletId, start, end, status, method)
                .innerJoin("payment.receivedBy", "receiver")
                .selectNew(CashierRow.class)
                .with("receiver.id")
                .with("receiver.fullName")
                .with("COUNT(payment.id)")
                .with("COALESCE(SUM(salesOrder.subtotal - salesOrder.discountAmount), 0)")
                .with("COALESCE(SUM(payment.amountPaid - payment.changeAmount), 0)")
                .end()
                .groupBy("receiver.id")
                .groupBy("receiver.fullName")
                .orderByDesc("SUM(salesOrder.subtotal - salesOrder.discountAmount)")
                .orderByAsc("receiver.fullName")
                .getResultList();
    }

    public List<ShiftRow> shifts(UUID tenantId, UUID outletId, Instant start, Instant end,
                                 OrderStatus status, PaymentMethod method) {
        return paidSalesQuery(tenantId, outletId, start, end, status, method)
                .leftJoinOn("payment.cashShift", "cashShift")
                .on("cashShift.tenant.id").eq(tenantId)
                .on("cashShift.outlet.id").eq(outletId)
                .end()
                .leftJoin("cashShift.openedBy", "opener")
                .selectNew(ShiftRow.class)
                .with("cashShift.id")
                .with("opener.fullName")
                .with("cashShift.openedAt")
                .with("cashShift.closedAt")
                .with("cashShift.status")
                .with("COUNT(payment.id)")
                .with("COALESCE(SUM(salesOrder.subtotal - salesOrder.discountAmount), 0)")
                .end()
                .groupBy("cashShift.id")
                .groupBy("opener.fullName")
                .groupBy("cashShift.openedAt")
                .groupBy("cashShift.closedAt")
                .groupBy("cashShift.status")
                .orderBy("cashShift.openedAt", false, false)
                .getResultList();
    }

    private CriteriaBuilder<Tuple> paidSalesQuery(UUID tenantId, UUID outletId, Instant start, Instant end,
                                                   OrderStatus status, PaymentMethod method) {
        CriteriaBuilder<Tuple> query = criteriaBuilderFactory.create(entityManager, Tuple.class)
                .from(PaymentEntity.class, "payment")
                .innerJoin("payment.salesOrder", "salesOrder")
                .where("payment.tenant.id").eq(tenantId)
                .where("payment.outlet.id").eq(outletId)
                .where("salesOrder.tenant.id").eq(tenantId)
                .where("salesOrder.outlet.id").eq(outletId)
                .where("payment.paidAt").ge(start)
                .where("payment.paidAt").lt(end)
                .where("salesOrder.paymentStatus").eq(PaymentStatus.PAID)
                .where("salesOrder.status").notEq(OrderStatus.CANCELLED);
        if (status != null) {
            query.where("salesOrder.status").eq(status);
        }
        if (method != null) {
            query.where("payment.method").eq(method);
        }
        return query;
    }

    public static class TransactionRow {
        public final UUID orderId;
        public final String orderNumber;
        public final OrderStatus status;
        public final PaymentMethod method;
        public final String methodLabel;
        public final BigDecimal subtotal;
        public final BigDecimal total;
        public final BigDecimal amountPaid;
        public final BigDecimal change;
        public final BigDecimal discount;
        public final BigDecimal tax;
        public final BigDecimal service;
        public final String receipt;
        public final Instant paidAt;

        public TransactionRow(UUID orderId, String orderNumber, OrderStatus status, PaymentMethod method,
                              String methodLabel, BigDecimal subtotal, BigDecimal total, BigDecimal amountPaid, BigDecimal change,
                              BigDecimal discount, BigDecimal tax, BigDecimal service, String receipt, Instant paidAt) {
            this.orderId = orderId;
            this.orderNumber = orderNumber;
            this.status = status;
            this.method = method;
            this.methodLabel = methodLabel;
            this.subtotal = subtotal;
            this.total = total;
            this.amountPaid = amountPaid;
            this.change = change;
            this.discount = discount;
            this.tax = tax;
            this.service = service;
            this.receipt = receipt;
            this.paidAt = paidAt;
        }
    }

    public static class SummaryRow {
        public final BigDecimal subtotal;
        public final BigDecimal netSales;
        public final BigDecimal total;
        public final BigDecimal amountCollected;
        public final long count;
        public final BigDecimal discount;
        public final BigDecimal tax;
        public final BigDecimal service;

        public SummaryRow(BigDecimal subtotal, BigDecimal netSales, BigDecimal total, BigDecimal amountCollected,
                          Long count, BigDecimal discount, BigDecimal tax, BigDecimal service) {
            this.subtotal = subtotal;
            this.netSales = netSales;
            this.total = total;
            this.amountCollected = amountCollected;
            this.count = count;
            this.discount = discount;
            this.tax = tax;
            this.service = service;
        }
    }

    public static class ProductRow {
        public final UUID productId;
        public final String name;
        public final long quantity;
        public final BigDecimal grossSales;
        public final BigDecimal cost;
        public final long itemsWithoutCost;

        public ProductRow(UUID productId, String name, Long quantity, BigDecimal grossSales,
                          BigDecimal cost, Long itemsWithoutCost) {
            this.productId = productId;
            this.name = name;
            this.quantity = quantity;
            this.grossSales = grossSales;
            this.cost = cost;
            this.itemsWithoutCost = itemsWithoutCost;
        }
    }

    public static class CostRow {
        public final BigDecimal cost;
        public final long itemsWithoutCost;

        public CostRow(BigDecimal cost, Long itemsWithoutCost) {
            this.cost = cost;
            this.itemsWithoutCost = itemsWithoutCost;
        }
    }

    public static class HourlyRow {
        public final int hour;
        public final long count;
        public final BigDecimal netSales;

        public HourlyRow(int hour, long count, BigDecimal netSales) {
            this.hour = hour;
            this.count = count;
            this.netSales = netSales;
        }
    }

    public static class CashierRow {
        public final UUID staffUserId;
        public final String fullName;
        public final long count;
        public final BigDecimal netSales;
        public final BigDecimal amountCollected;

        public CashierRow(UUID staffUserId, String fullName, Long count, BigDecimal netSales, BigDecimal amountCollected) {
            this.staffUserId = staffUserId;
            this.fullName = fullName;
            this.count = count;
            this.netSales = netSales;
            this.amountCollected = amountCollected;
        }
    }

    public static class ShiftRow {
        public final UUID shiftId;
        public final String cashierName;
        public final Instant openedAt;
        public final Instant closedAt;
        public final CashShiftEntity.Status status;
        public final long count;
        public final BigDecimal netSales;

        public ShiftRow(UUID shiftId, String cashierName, Instant openedAt, Instant closedAt,
                        CashShiftEntity.Status status, Long count, BigDecimal netSales) {
            this.shiftId = shiftId;
            this.cashierName = cashierName;
            this.openedAt = openedAt;
            this.closedAt = closedAt;
            this.status = status;
            this.count = count;
            this.netSales = netSales;
        }
    }

    public static class MethodRow {
        public final PaymentMethod method;
        public final String methodLabel;
        public final long count;
        public final BigDecimal netSales;
        public final BigDecimal amountCollected;

        public MethodRow(PaymentMethod method, String methodLabel, Long count, BigDecimal netSales, BigDecimal amountCollected) {
            this.method = method;
            this.methodLabel = methodLabel;
            this.count = count;
            this.netSales = netSales;
            this.amountCollected = amountCollected;
        }
    }
}
