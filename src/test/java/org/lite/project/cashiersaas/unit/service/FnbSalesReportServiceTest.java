package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.FnbSalesReportFilter;
import org.lite.project.cashiersaas.dto.response.FnbSalesReportResponse;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentMethod;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.repository.FnbRefundRepository;
import org.lite.project.cashiersaas.repository.FnbRefundRepository.RefundTotals;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository.CostRow;
import org.lite.project.cashiersaas.repository.FnbSalesReportRepository.SummaryRow;
import org.lite.project.cashiersaas.service.FnbSalesReportService;
import org.lite.project.cashiersaas.service.MerchantAccessGuard;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void refundsAreQueriedByDateRangeOnlyWhileSalesDataKeepsTheOrderAndMethodFilters() {
        when(guard.requireOwner(owner, outletId)).thenReturn(context);
        when(reports.summary(tenantId, outletId, start, end, null, PaymentMethod.MANUAL_NON_CASH))
                .thenReturn(new SummaryRow(bd("0"), bd("0"), bd("0"), bd("0"), 0L, bd("0"), bd("0"), bd("0")));
        when(reports.cost(tenantId, outletId, start, end, null, PaymentMethod.MANUAL_NON_CASH))
                .thenReturn(new CostRow(bd("0"), 0L));
        when(refunds.totals(tenantId, outletId, start, end)).thenReturn(new RefundTotals(0L, bd("0")));

        FnbSalesReportResponse response = service.report(owner, outletId, filter(null, PaymentMethod.MANUAL_NON_CASH));

        assertEquals("Asia/Jakarta", response.getTimeZone());
        verify(reports).products(tenantId, outletId, start, end, null, PaymentMethod.MANUAL_NON_CASH, 10);
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
