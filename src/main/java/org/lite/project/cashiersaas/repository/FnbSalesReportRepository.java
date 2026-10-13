package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilder;
import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.fnb.OrderItemEntity;
import org.lite.project.cashiersaas.entity.fnb.PaymentEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentMethod;
import org.lite.project.cashiersaas.enums.PaymentStatus;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
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
                .with("COUNT(payment.id)")
                .with("COALESCE(SUM(salesOrder.subtotal - salesOrder.discountAmount), 0)")
                .with("COALESCE(SUM(payment.amountPaid - payment.changeAmount), 0)")
                .end()
                .groupBy("payment.method")
                .orderByAsc("payment.method")
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
                              BigDecimal subtotal, BigDecimal total, BigDecimal amountPaid, BigDecimal change,
                              BigDecimal discount, BigDecimal tax, BigDecimal service, String receipt, Instant paidAt) {
            this.orderId = orderId;
            this.orderNumber = orderNumber;
            this.status = status;
            this.method = method;
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
                          long count, BigDecimal discount, BigDecimal tax, BigDecimal service) {
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

        public ProductRow(UUID productId, String name, long quantity, BigDecimal grossSales) {
            this.productId = productId;
            this.name = name;
            this.quantity = quantity;
            this.grossSales = grossSales;
        }
    }

    public static class MethodRow {
        public final PaymentMethod method;
        public final long count;
        public final BigDecimal netSales;
        public final BigDecimal amountCollected;

        public MethodRow(PaymentMethod method, long count, BigDecimal netSales, BigDecimal amountCollected) {
            this.method = method;
            this.count = count;
            this.netSales = netSales;
            this.amountCollected = amountCollected;
        }
    }
}
