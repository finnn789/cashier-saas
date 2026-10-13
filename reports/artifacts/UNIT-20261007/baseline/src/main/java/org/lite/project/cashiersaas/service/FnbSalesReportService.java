package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.FnbSalesReportMapper;
import org.lite.project.cashiersaas.dto.request.FnbSalesReportFilter;
import org.lite.project.cashiersaas.dto.response.FnbSalesCsvResponse;
import org.lite.project.cashiersaas.dto.response.FnbSalesReportResponse;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
    private final MerchantAccessGuard guard;
    private final FnbSalesReportRepository reports;

    @Transactional(readOnly = true)
    public FnbSalesReportResponse report(TenantPrincipal principal, UUID outletId, FnbSalesReportFilter filter) {
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        Query query = query(context, filter);
        List<FnbSalesReportRepository.TransactionRow> rows = reports.transactions(principal.getTenantId(), outletId,
                query.start, query.end, filter.getOrderStatus(), filter.getPaymentMethod(), query.page, query.size);
        boolean hasNext = rows.size() > query.size;
        if (hasNext) rows = rows.subList(0, query.size);
        FnbSalesReportResponse value = new FnbSalesReportResponse();
        value.setStartDate(query.startDate); value.setEndDate(query.endDate); value.setTimeZone(query.zone.getId());
        value.setSummary(FnbSalesReportMapper.summary(reports.summary(principal.getTenantId(), outletId, query.start,
                query.end, filter.getOrderStatus(), filter.getPaymentMethod()),
                reports.cancellations(principal.getTenantId(), outletId, query.start, query.end)));
        value.setTransactions(rows.stream().map(row -> FnbSalesReportMapper.transaction(row, query.zone)).toList());
        value.setTopProducts(FnbSalesReportMapper.products(reports.products(principal.getTenantId(), outletId,
                query.start, query.end, filter.getOrderStatus(), filter.getPaymentMethod(), query.top)));
        value.setPaymentBreakdown(FnbSalesReportMapper.methods(reports.methods(principal.getTenantId(), outletId,
                query.start, query.end, filter.getOrderStatus(), filter.getPaymentMethod())));
        value.setPage(query.page); value.setSize(query.size); value.setHasNext(hasNext);
        return value;
    }

    @Transactional(readOnly = true)
    public FnbSalesCsvResponse csv(TenantPrincipal principal, UUID outletId, FnbSalesReportFilter filter) {
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        Query query = query(context, filter);
        List<FnbSalesReportRepository.TransactionRow> rows = reports.transactions(principal.getTenantId(), outletId,
                query.start, query.end, filter.getOrderStatus(), filter.getPaymentMethod(), 0, MAX_CSV_ROWS);
        if (rows.size() > MAX_CSV_ROWS) throw new InvalidOperationException("Ekspor melebihi batas 10000 baris; persempit rentang tanggal.");
        StringBuilder content = new StringBuilder("Order Number,Receipt Number,Status,Payment Method,Paid At,Net Sales,Total,Amount Collected,Discount,Tax,Service Charge\r\n");
        for (FnbSalesReportRepository.TransactionRow row : rows) {
            content.append(csv(row.orderNumber)).append(',').append(csv(row.receipt)).append(',')
                    .append(csv(String.valueOf(row.status))).append(',').append(csv(String.valueOf(row.method))).append(',')
                    .append(csv(row.paidAt.atZone(query.zone).toString())).append(',')
                    .append(csv(row.subtotal.subtract(row.discount).toPlainString())).append(',')
                    .append(csv(row.total.toPlainString())).append(',')
                    .append(csv(row.amountPaid.subtract(row.change).toPlainString())).append(',')
                    .append(csv(row.discount.toPlainString())).append(',').append(csv(row.tax.toPlainString())).append(',')
                    .append(csv(row.service.toPlainString())).append("\r\n");
        }
        FnbSalesCsvResponse value = new FnbSalesCsvResponse();
        value.setFilename("sales-" + query.startDate + "-" + query.endDate + ".csv");
        value.setContentType("text/csv; charset=UTF-8"); value.setContent(content.toString());
        return value;
    }

    private Query query(MerchantAccessContext context, FnbSalesReportFilter filter) {
        ZoneId zone;
        try { zone = ZoneId.of(context.getOutlet().getTimeZone()); } catch (DateTimeException exception) { throw new InvalidOperationException("Zona waktu outlet tidak valid."); }
        LocalDate today = LocalDate.now(zone);
        LocalDate startDate = filter.getStartDate() == null ? today : filter.getStartDate();
        LocalDate endDate = filter.getEndDate() == null ? today : filter.getEndDate();
        if (endDate.isBefore(startDate) || ChronoUnit.DAYS.between(startDate, endDate) >= MAX_DAYS) throw new InvalidOperationException("Rentang tanggal laporan tidak valid.");
        if (filter.getOrderStatus() == OrderStatus.NEW || filter.getOrderStatus() == OrderStatus.CANCELLED) throw new InvalidOperationException("Filter status tidak berlaku untuk laporan penjualan.");
        int page = filter.getPage() == null ? 0 : filter.getPage(), size = filter.getSize() == null ? 20 : filter.getSize(), top = filter.getTopProducts() == null ? 10 : filter.getTopProducts();
        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_SIZE || top < 1 || top > MAX_TOP) throw new InvalidOperationException("Batas laporan tidak valid.");
        try { return new Query(startDate, endDate, zone, startDate.atStartOfDay(zone).toInstant(), endDate.plusDays(1).atStartOfDay(zone).toInstant(), page, size, top); } catch (DateTimeException exception) { throw new InvalidOperationException("Rentang tanggal laporan tidak valid."); }
    }
    private String csv(String value) {
        String safe = value == null ? "" : value; int index = 0;
        while (index < safe.length() && (Character.isWhitespace(safe.charAt(index)) || Character.isSpaceChar(safe.charAt(index)))) index++;
        if (index < safe.length() && "=+-@".indexOf(safe.charAt(index)) >= 0) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
    private static class Query {
        final LocalDate startDate, endDate; final ZoneId zone; final Instant start, end; final int page, size, top;
        Query(LocalDate startDate, LocalDate endDate, ZoneId zone, Instant start, Instant end, int page, int size, int top) { this.startDate = startDate; this.endDate = endDate; this.zone = zone; this.start = start; this.end = end; this.page = page; this.size = size; this.top = top; }
    }
}
