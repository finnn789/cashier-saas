package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.FnbSalesReportFilter;
import org.lite.project.cashiersaas.dto.response.FnbSalesReportResponse;
import org.lite.project.cashiersaas.entity.fnb.CashShiftEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentMethod;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.repository.FnbRefundRepository;
import org.lite.project.cashiersaas.repository.FnbRefundRepository.RefundTotals;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository.CashierRow;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository.CostRow;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository.HourlyRow;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository.ProductRow;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository.ShiftRow;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository.SummaryRow;
import org.lite.project.cashiersaas.service.FnbSalesReportService;
import org.lite.project.cashiersaas.service.MerchantAccessGuard;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** M6 (TASK-079): wiring of COGS, refunds, hourly, cashier and shift data in {@code FnbSalesReportService.report}. */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class FnbSalesReportServiceTest {
    @Mock private MerchantAccessGuard guard;
    @Mock private FnbSalesReportRepository reports;
    @Mock private FnbRefundRepository refunds;
    @InjectMocks private FnbSalesReportService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID outletId = UUID.randomUUID();
    private final TenantPrincipal owner = new TenantPrincipal(UUID.randomUUID(), tenantId, outletId,
            UUID.randomUUID(), UUID.randomUUID(), Set.of(Role.OWNERMERCHANT));
    private final TenantEntity tenant = new TenantEntity();
    private final OutletEntity outlet = OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null);
    private final MerchantAccessContext context = new MerchantAccessContext(tenant, outlet,
            TenantMembershipEntity.grantOwner(null, tenant));
    // 2026-11-01 .. 2026-11-03 in Asia/Jakarta (UTC+7), end exclusive
    private final Instant start = Instant.parse("2026-10-31T17:00:00Z");
    private final Instant end = Instant.parse("2026-11-03T17:00:00Z");

    private FnbSalesReportFilter filter(OrderStatus status, PaymentMethod method) {
        FnbSalesReportFilter filter = new FnbSalesReportFilter();
        filter.setStartDate(LocalDate.of(2026, 11, 1));
        filter.setEndDate(LocalDate.of(2026, 11, 3));
        filter.setOrderStatus(status);
        filter.setPaymentMethod(method);
        return filter;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Test
    void reportWiresCostRefundsAndBreakdownsIntoTheResponseUsingTheOutletZone() {
        UUID cashierId = UUID.randomUUID();
        UUID shiftId = UUID.randomUUID();
        when(guard.requireOwner(owner, outletId)).thenReturn(context);
        when(reports.summary(tenantId, outletId, start, end, OrderStatus.COMPLETED, PaymentMethod.CASH))
                .thenReturn(new SummaryRow(bd("1000.00"), bd("900.00"), bd("900.00"), bd("1000.00"), 4L,
                        bd("100.00"), bd("0"), bd("0")));
        when(reports.cancellations(tenantId, outletId, start, end)).thenReturn(1L);
        when(reports.cost(tenantId, outletId, start, end, OrderStatus.COMPLETED, PaymentMethod.CASH))
                .thenReturn(new CostRow(bd("400.00"), 2L));
        when(refunds.totals(tenantId, outletId, start, end)).thenReturn(new RefundTotals(1L, bd("50.00")));
        when(reports.hourly(tenantId, outletId, start, end, "Asia/Jakarta", OrderStatus.COMPLETED, PaymentMethod.CASH))
                .thenReturn(List.of(new HourlyRow(9, 3L, bd("600.00")), new HourlyRow(14, 1L, bd("300.00"))));
        when(reports.cashiers(tenantId, outletId, start, end, OrderStatus.COMPLETED, PaymentMethod.CASH))
                .thenReturn(List.of(new CashierRow(cashierId, "Sari", 4L, bd("900.00"), bd("1000.00"))));
        when(reports.shifts(tenantId, outletId, start, end, OrderStatus.COMPLETED, PaymentMethod.CASH))
                .thenReturn(List.of(
                        new ShiftRow(shiftId, "Sari", start, end, CashShiftEntity.Status.CLOSED, 3L, bd("700.00")),
                        new ShiftRow(null, null, null, null, null, 1L, bd("200.00"))));
        when(reports.products(tenantId, outletId, start, end, OrderStatus.COMPLETED, PaymentMethod.CASH, 10))
                .thenReturn(List.of(new ProductRow(UUID.randomUUID(), "Espresso", 6L, bd("600.00"), bd("150.00"), 1L)));

        FnbSalesReportResponse response = service.report(owner, outletId, filter(OrderStatus.COMPLETED, PaymentMethod.CASH));

        assertEquals("Asia/Jakarta", response.getTimeZone());
        assertEquals(bd("400.00"), response.getSummary().getCostOfGoodsSold());
        assertEquals(bd("500.00"), response.getSummary().getGrossProfit());
        assertEquals(2L, response.getSummary().getItemsWithoutCost());
        assertEquals(1L, response.getSummary().getRefundCount());
        assertEquals(bd("50.00"), response.getSummary().getRefundAmount());
        assertEquals(1L, response.getSummary().getCancellationCount());
        assertEquals(List.of(9, 14), response.getHourly().stream().map(value -> value.getHour()).toList());
        assertEquals(bd("600.00"), response.getHourly().get(0).getNetSales());
        assertEquals(cashierId, response.getCashiers().get(0).getStaffUserId());
        assertEquals(bd("1000.00"), response.getCashiers().get(0).getAmountCollected());
        assertEquals(2, response.getShifts().size());
        assertEquals(shiftId, response.getShifts().get(0).getShiftId());
        assertEquals("CLOSED", response.getShifts().get(0).getStatus());
        assertNull(response.getShifts().get(1).getShiftId());
        assertEquals(bd("450.00"), response.getTopProducts().get(0).getGrossProfit());
        assertEquals(1L, response.getTopProducts().get(0).getItemsWithoutCost());
    }

    @Test
    void refundsAreQueriedByDateRangeOnlyWhileSalesDataKeepsTheOrderAndMethodFilters() {
        when(guard.requireOwner(owner, outletId)).thenReturn(context);
        when(reports.summary(tenantId, outletId, start, end, null, PaymentMethod.MANUAL_NON_CASH))
                .thenReturn(new SummaryRow(bd("0"), bd("0"), bd("0"), bd("0"), 0L, bd("0"), bd("0"), bd("0")));
        when(reports.cost(tenantId, outletId, start, end, null, PaymentMethod.MANUAL_NON_CASH))
                .thenReturn(new CostRow(bd("0"), 0L));
        when(refunds.totals(tenantId, outletId, start, end)).thenReturn(new RefundTotals(0L, bd("0")));

        FnbSalesReportResponse response = service.report(owner, outletId, filter(null, PaymentMethod.MANUAL_NON_CASH));

        // refunds.totals has no status/method parameters at all: the figure is not narrowed by the report filters
        verify(refunds).totals(tenantId, outletId, start, end);
        verify(reports).cost(tenantId, outletId, start, end, null, PaymentMethod.MANUAL_NON_CASH);
        verify(reports).hourly(tenantId, outletId, start, end, "Asia/Jakarta", null, PaymentMethod.MANUAL_NON_CASH);
        verify(reports).cashiers(tenantId, outletId, start, end, null, PaymentMethod.MANUAL_NON_CASH);
        verify(reports).shifts(tenantId, outletId, start, end, null, PaymentMethod.MANUAL_NON_CASH);
        assertEquals(0, response.getHourly().size());
        assertEquals(0, response.getCashiers().size());
        assertEquals(0, response.getShifts().size());
        assertEquals(0L, response.getSummary().getRefundCount());
    }
}
