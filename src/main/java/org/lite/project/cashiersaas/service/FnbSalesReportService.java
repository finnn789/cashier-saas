package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.FnbSalesReportMapper;
import org.lite.project.cashiersaas.dto.request.FnbSalesReportFilter;
import org.lite.project.cashiersaas.dto.response.FnbConsolidatedSalesReportResponse;
import org.lite.project.cashiersaas.dto.response.FnbOutletSalesSummaryResponse;
import org.lite.project.cashiersaas.dto.response.FnbSalesCsvResponse;
import org.lite.project.cashiersaas.dto.response.FnbSalesReportResponse;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentMethod;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.PlanUpgradeRequiredException;
import org.lite.project.cashiersaas.repository.FnbRefundRepository;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository;
import org.lite.project.cashiersaas.repository.MerchantAccessRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FnbSalesReportService {
    private static final int MAX_DAYS = 366;
    private static final int MAX_PAGE = 1_000_000;
    private static final int MAX_SIZE = 100;
    private static final int MAX_TOP = 50;
    private static final int MAX_CSV_ROWS = 10_000;
    private static final long FREE_WINDOW_DAYS = 29;
    private final MerchantAccessGuard guard;
    private final FnbSalesReportRepository reports;
    private final FnbRefundRepository refunds;
    private final PlanEntitlementService planEntitlementService;
    private final MerchantAccessRepository accessRepository;

    @Transactional(readOnly = true)
    public FnbSalesReportResponse report(TenantPrincipal principal, UUID outletId, FnbSalesReportFilter filter) {
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        Query query = query(context, filter);
        boolean full = planEntitlementService.effectivePlan(principal.getTenantId(), Instant.now()) != PlanCode.FREETIER;
        requireWithinPlanWindow(full, query);
        return assemble(principal.getTenantId(), outletId, query, filter.getOrderStatus(), filter.getPaymentMethod(), full);
    }

    /**
     * Owner-only totals over every usable outlet the owner can access; each outlet reads the dates in its own zone.
     */
    @Transactional(readOnly = true)
    public FnbConsolidatedSalesReportResponse consolidated(TenantPrincipal principal, FnbSalesReportFilter filter) {
        guard.requireOwnerIgnoringAllowance(principal, principal.getOutletId());
        Instant now = Instant.now();
        boolean full = planEntitlementService.effectivePlan(principal.getTenantId(), now) != PlanCode.FREETIER;
        FnbSalesReportFilter perOutlet = new FnbSalesReportFilter();
        perOutlet.setStartDate(filter.getStartDate());
        perOutlet.setEndDate(filter.getEndDate());
        perOutlet.setOrderStatus(filter.getOrderStatus());
        perOutlet.setPaymentMethod(filter.getPaymentMethod());
        perOutlet.setPage(0);
        perOutlet.setSize(1);
        perOutlet.setTopProducts(1);
        List<FnbOutletSalesSummaryResponse> outlets = new ArrayList<>();
        LocalDate startDate = null, endDate = null;
        for (UUID outletId : planEntitlementService.usableOutletIds(principal.getTenantId(), now)) {
            MerchantAccessRepository.OutletAccess access = accessRepository.findOutlet(principal.getUserId(),
                    principal.getTenantId(), outletId, principal.getRoles(), now).orElse(null);
            if (access == null || access.getMembership().getRole() != Role.OWNERMERCHANT) continue;
            MerchantAccessContext context = new MerchantAccessContext(access.getMembership().getTenant(), access.getOutlet(), access.getMembership());
            Query query = query(context, perOutlet);
            requireWithinPlanWindow(full, query);
            FnbOutletSalesSummaryResponse item = new FnbOutletSalesSummaryResponse();
            item.setOutletId(outletId);
            item.setOutletName(access.getOutlet().getName());
            item.setTimeZone(query.zone.getId());
            item.setSummary(assemble(principal.getTenantId(), outletId, query, filter.getOrderStatus(), filter.getPaymentMethod(), false).getSummary());
            outlets.add(item);
            if (startDate == null || query.startDate.isBefore(startDate)) startDate = query.startDate;
            if (endDate == null || query.endDate.isAfter(endDate)) endDate = query.endDate;
        }
        FnbConsolidatedSalesReportResponse value = new FnbConsolidatedSalesReportResponse();
        value.setStartDate(startDate);
        value.setEndDate(endDate);
        value.setOutlets(outlets);
        value.setTotals(FnbSalesReportMapper.sum(outlets.stream().map(FnbOutletSalesSummaryResponse::getSummary).toList()));
        return value;
    }

    private void requireWithinPlanWindow(boolean full, Query query) {
        if (!full && query.startDate.isBefore(LocalDate.now(query.zone).minusDays(FREE_WINDOW_DAYS))) {
            throw new PlanUpgradeRequiredException(PlanCode.PRO);
        }
    }

    /**
     * Owner-side read model for an already authorised context: no status/method filter, one transaction row.
     */
    @Transactional(readOnly = true)
    public FnbSalesReportResponse summarize(MerchantAccessContext context, LocalDate startDate, LocalDate endDate, int topProducts) {
        FnbSalesReportFilter filter = new FnbSalesReportFilter();
        filter.setStartDate(startDate);
        filter.setEndDate(endDate);
        filter.setPage(0);
        filter.setSize(1);
        filter.setTopProducts(topProducts);
        return assemble(context.getTenant().getId(), context.getOutlet().getId(), query(context, filter), null, null, true);
    }

    /**
     * {@code full=false} (FREETIER) keeps summary, transactions, products and payment mix but empties hourly, cashiers and shifts.
     */
    private FnbSalesReportResponse assemble(UUID tenantId, UUID outletId, Query query, OrderStatus status, PaymentMethod method, boolean full) {
        List<FnbSalesReportRepository.TransactionRow> rows = reports.transactions(tenantId, outletId,
                query.start, query.end, status, method, query.page, query.size);
        boolean hasNext = rows.size() > query.size;
        if (hasNext) rows = rows.subList(0, query.size);
        FnbSalesReportResponse value = new FnbSalesReportResponse();
        value.setStartDate(query.startDate);
        value.setEndDate(query.endDate);
        value.setTimeZone(query.zone.getId());
        value.setSummary(FnbSalesReportMapper.summary(reports.summary(tenantId, outletId, query.start, query.end, status, method),
                reports.cancellations(tenantId, outletId, query.start, query.end),
                reports.cost(tenantId, outletId, query.start, query.end, status, method),
                refunds.totals(tenantId, outletId, query.start, query.end)));
        if (full) {
            value.setHourly(FnbSalesReportMapper.hourly(reports.hourly(tenantId, outletId, query.start,
                    query.end, query.zone.getId(), status, method)));
            value.setCashiers(FnbSalesReportMapper.cashiers(reports.cashiers(tenantId, outletId, query.start, query.end, status, method)));
            value.setShifts(FnbSalesReportMapper.shifts(reports.shifts(tenantId, outletId, query.start, query.end, status, method)));
        } else {
            value.setHourly(List.of());
            value.setCashiers(List.of());
            value.setShifts(List.of());
        }
        value.setTransactions(rows.stream().map(row -> FnbSalesReportMapper.transaction(row, query.zone)).toList());
        value.setTopProducts(FnbSalesReportMapper.products(reports.products(tenantId, outletId,
                query.start, query.end, status, method, query.top)));
        value.setPaymentBreakdown(FnbSalesReportMapper.methods(reports.methods(tenantId, outletId, query.start, query.end, status, method)));
        value.setPage(query.page);
        value.setSize(query.size);
        value.setHasNext(hasNext);
        return value;
    }

    @Transactional(readOnly = true)
    public FnbSalesCsvResponse csv(TenantPrincipal principal, UUID outletId, FnbSalesReportFilter filter) {
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        planEntitlementService.require(principal.getTenantId(), PlanCode.PRO);
        Query query = query(context, filter);
        List<FnbSalesReportRepository.TransactionRow> rows = reports.transactions(principal.getTenantId(), outletId,
                query.start, query.end, filter.getOrderStatus(), filter.getPaymentMethod(), 0, MAX_CSV_ROWS);
        if (rows.size() > MAX_CSV_ROWS)
            throw new InvalidOperationException("Ekspor melebihi batas 10000 baris; persempit rentang tanggal.");
        StringBuilder content = new StringBuilder("Order Number,Receipt Number,Status,Payment Method,Payment Label,Paid At,Net Sales,Total,Amount Collected,Discount,Tax,Service Charge\r\n");
        for (FnbSalesReportRepository.TransactionRow row : rows) {
            content.append(csv(row.orderNumber)).append(',').append(csv(row.receipt)).append(',')
                    .append(csv(String.valueOf(row.status))).append(',').append(csv(String.valueOf(row.method))).append(',').append(csv(row.methodLabel)).append(',')
                    .append(csv(row.paidAt.atZone(query.zone).toString())).append(',')
                    .append(csv(row.subtotal.subtract(row.discount).toPlainString())).append(',')
                    .append(csv(row.total.toPlainString())).append(',')
                    .append(csv(row.amountPaid.subtract(row.change).toPlainString())).append(',')
                    .append(csv(row.discount.toPlainString())).append(',').append(csv(row.tax.toPlainString())).append(',')
                    .append(csv(row.service.toPlainString())).append("\r\n");
        }
        FnbSalesCsvResponse value = new FnbSalesCsvResponse();
        value.setFilename("sales-" + query.startDate + "-" + query.endDate + ".csv");
        value.setContentType("text/csv; charset=UTF-8");
        value.setContent(content.toString());
        return value;
    }

    /**
     * Shared date-range rule of the owner reports: end not before start and fewer than 366 days.
     */
    public void requireValidRange(LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate) || ChronoUnit.DAYS.between(startDate, endDate) >= MAX_DAYS)
            throw new InvalidOperationException("Rentang tanggal laporan tidak valid.");
    }

    private Query query(MerchantAccessContext context, FnbSalesReportFilter filter) {
        ZoneId zone;
        try {
            zone = ZoneId.of(context.getOutlet().getTimeZone());
        } catch (DateTimeException exception) {
            throw new InvalidOperationException("Zona waktu outlet tidak valid.");
        }
        LocalDate today = LocalDate.now(zone);
        LocalDate startDate = filter.getStartDate() == null ? today : filter.getStartDate();
        LocalDate endDate = filter.getEndDate() == null ? today : filter.getEndDate();
        requireValidRange(startDate, endDate);
        if (filter.getOrderStatus() == OrderStatus.OPEN || filter.getOrderStatus() == OrderStatus.CANCELLED)
            throw new InvalidOperationException("Filter status tidak berlaku untuk laporan penjualan.");
        int page = filter.getPage() == null ? 0 : filter.getPage(), size = filter.getSize() == null ? 20 : filter.getSize(), top = filter.getTopProducts() == null ? 10 : filter.getTopProducts();
        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_SIZE || top < 1 || top > MAX_TOP)
            throw new InvalidOperationException("Batas laporan tidak valid.");
        try {
            return new Query(startDate, endDate, zone, startDate.atStartOfDay(zone).toInstant(), endDate.plusDays(1).atStartOfDay(zone).toInstant(), page, size, top);
        } catch (DateTimeException exception) {
            throw new InvalidOperationException("Rentang tanggal laporan tidak valid.");
        }
    }

    private String csv(String value) {
        String safe = value == null ? "" : value;
        int index = 0;
        while (index < safe.length() && (Character.isWhitespace(safe.charAt(index)) || Character.isSpaceChar(safe.charAt(index))))
            index++;
        if (index < safe.length() && "=+-@".indexOf(safe.charAt(index)) >= 0) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static class Query {
        final LocalDate startDate, endDate;
        final ZoneId zone;
        final Instant start, end;
        final int page, size, top;

        Query(LocalDate startDate, LocalDate endDate, ZoneId zone, Instant start, Instant end, int page, int size, int top) {
            this.startDate = startDate;
            this.endDate = endDate;
            this.zone = zone;
            this.start = start;
            this.end = end;
            this.page = page;
            this.size = size;
            this.top = top;
        }
    }
}
