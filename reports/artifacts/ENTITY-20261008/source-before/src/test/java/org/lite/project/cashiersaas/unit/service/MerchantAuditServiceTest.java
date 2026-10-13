package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.MerchantAuditFilter;
import org.lite.project.cashiersaas.dto.response.MerchantAuditPageResponse;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.MerchantAuditRepository;
import org.lite.project.cashiersaas.service.MerchantAccessGuard;
import org.lite.project.cashiersaas.service.MerchantAuditService;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class MerchantAuditServiceTest {
    @Mock private MerchantAccessGuard guard;
    @Mock private MerchantAuditRepository audits;
    @InjectMocks private MerchantAuditService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID outletId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final TenantPrincipal owner = new TenantPrincipal(UUID.randomUUID(), tenantId, outletId,
            UUID.randomUUID(), UUID.randomUUID(), Set.of(Role.OWNERMERCHANT));

    @Test
    void guardDenialDoesNotAccessAuditRepository() {
        when(guard.requireOwner(owner, outletId)).thenThrow(new ResourceNotFoundException());

        assertThrows(ResourceNotFoundException.class, () -> service.list(owner, outletId, new MerchantAuditFilter()));

        verifyNoInteractions(audits);
    }

    @Test
    void forwardsAllScopedPredicatesIdenticallyToRowsAndCount() {
        MerchantAuditFilter filter = filter(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), 3, 7);
        filter.setAction("FNB_PAYMENT_RECORDED");
        filter.setActorId(actorId);
        filter.setEntityType("SALES_ORDER");
        allowOwner("Asia/Jakarta");
        when(audits.page(eq(tenantId), eq(outletId), any(), any(), eq(filter.getAction()), eq(actorId),
                eq(filter.getEntityType()), eq(3), eq(7))).thenReturn(List.of());
        when(audits.count(eq(tenantId), eq(outletId), any(), any(), eq(filter.getAction()), eq(actorId),
                eq(filter.getEntityType()))).thenReturn(0L);

        service.list(owner, outletId, filter);

        ArgumentCaptor<Instant> starts = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> ends = ArgumentCaptor.forClass(Instant.class);
        verify(audits).page(eq(tenantId), eq(outletId), starts.capture(), ends.capture(), eq(filter.getAction()),
                eq(actorId), eq(filter.getEntityType()), eq(3), eq(7));
        verify(audits).count(eq(tenantId), eq(outletId), eq(starts.getValue()), eq(ends.getValue()),
                eq(filter.getAction()), eq(actorId), eq(filter.getEntityType()));
        assertEquals(Instant.parse("2026-09-30T17:00:00Z"), starts.getValue());
        assertEquals(Instant.parse("2026-10-02T17:00:00Z"), ends.getValue());
    }

    @Test
    void defaultsPageAndSizeAndReturnsTotal() {
        MerchantAuditFilter filter = filter(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5), null, null);
        allowOwner("UTC");
        List<MerchantAuditRepository.AuditRow> rows = List.of(row(), row(), row());
        when(audits.page(eq(tenantId), eq(outletId), any(), any(), any(), any(), any(), eq(0), eq(20)))
                .thenReturn(rows);
        when(audits.count(eq(tenantId), eq(outletId), any(), any(), any(), any(), any())).thenReturn(23L);

        MerchantAuditPageResponse response = service.list(owner, outletId, filter);

        assertEquals(0, response.getPage());
        assertEquals(20, response.getSize());
        assertEquals(3, response.getEntries().size());
        assertFalse(response.isHasNext());
        assertEquals(23L, response.getTotal());
    }

    @Test
    void overflowRowIsTrimmedAndSignalsNextPage() {
        MerchantAuditFilter filter = filter(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5), 2, 2);
        allowOwner("UTC");
        when(audits.page(eq(tenantId), eq(outletId), any(), any(), any(), any(), any(), eq(2), eq(2)))
                .thenReturn(List.of(row(), row(), row()));
        when(audits.count(eq(tenantId), eq(outletId), any(), any(), any(), any(), any())).thenReturn(9L);

        MerchantAuditPageResponse response = service.list(owner, outletId, filter);

        assertEquals(2, response.getEntries().size());
        assertTrue(response.isHasNext());
        assertEquals(9L, response.getTotal());
    }

    @Test
    void createsHalfOpenOutletLocalInstantsAcrossDst() {
        MerchantAuditFilter filter = filter(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 3, 8), 0, 1);
        allowOwner("America/New_York");
        when(audits.page(eq(tenantId), eq(outletId), any(), any(), any(), any(), any(), eq(0), eq(1)))
                .thenReturn(List.of());
        when(audits.count(eq(tenantId), eq(outletId), any(), any(), any(), any(), any())).thenReturn(0L);

        service.list(owner, outletId, filter);

        verify(audits).page(eq(tenantId), eq(outletId),
                eq(Instant.parse("2026-03-08T05:00:00Z")), eq(Instant.parse("2026-03-09T04:00:00Z")),
                eq(null), eq(null), eq(null), eq(0), eq(1));
        verify(audits).count(tenantId, outletId, Instant.parse("2026-03-08T05:00:00Z"),
                Instant.parse("2026-03-09T04:00:00Z"), null, null, null);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidFilters")
    void rejectsInvalidDatePageSizeAndFilterBoundsBeforeQueries(String scenario, MerchantAuditFilter filter) {
        allowOwner("UTC");

        assertThrows(InvalidOperationException.class, () -> service.list(owner, outletId, filter), scenario);

        verifyNoInteractions(audits);
    }

    private static Stream<Arguments> invalidFilters() {
        LocalDate day = LocalDate.of(2026, 1, 1);
        MerchantAuditFilter longAction = filter(day, day, 0, 1);
        longAction.setAction("x".repeat(81));
        MerchantAuditFilter longEntity = filter(day, day, 0, 1);
        longEntity.setEntityType("x".repeat(81));
        return Stream.of(
                Arguments.of("reversed dates", filter(day.plusDays(1), day, 0, 1)),
                Arguments.of("367 inclusive leap-year days",
                        filter(LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1), 0, 1)),
                Arguments.of("exclusive end overflow", filter(LocalDate.MAX, LocalDate.MAX, 0, 1)),
                Arguments.of("negative page", filter(day, day, -1, 1)),
                Arguments.of("page above maximum", filter(day, day, 1_000_001, 1)),
                Arguments.of("zero size", filter(day, day, 0, 0)),
                Arguments.of("size above maximum", filter(day, day, 0, 101)),
                Arguments.of("action above 80 characters", longAction),
                Arguments.of("entity type above 80 characters", longEntity));
    }

    @ParameterizedTest
    @CsvSource({"0,1", "1000000,100"})
    void acceptsExactPageSizeAndTextBounds(int page, int size) {
        LocalDate day = LocalDate.of(2026, 1, 1);
        MerchantAuditFilter filter = filter(day, day, page, size);
        filter.setAction("a".repeat(80));
        filter.setEntityType("e".repeat(80));
        filter.setActorId(actorId);
        allowOwner("UTC");
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-01-02T00:00:00Z");
        when(audits.page(tenantId, outletId, start, end, filter.getAction(), actorId,
                filter.getEntityType(), page, size)).thenReturn(List.of());
        when(audits.count(tenantId, outletId, start, end, filter.getAction(), actorId,
                filter.getEntityType())).thenReturn(0L);

        MerchantAuditPageResponse response = service.list(owner, outletId, filter);

        assertEquals(page, response.getPage());
        assertEquals(size, response.getSize());
        assertFalse(response.isHasNext());
        verify(audits).page(tenantId, outletId, start, end, filter.getAction(), actorId,
                filter.getEntityType(), page, size);
        verify(audits).count(tenantId, outletId, start, end, filter.getAction(), actorId, filter.getEntityType());
    }

    @Test
    void rejectsInvalidOutletTimeZoneBeforeQueries() {
        allowOwner("not/a-zone");

        assertThrows(InvalidOperationException.class, () -> service.list(owner, outletId, new MerchantAuditFilter()));

        verifyNoInteractions(audits);
    }

    @Test
    void acceptsExactlyThreeHundredSixtySixDays() {
        MerchantAuditFilter filter = filter(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), 0, 1);
        allowOwner("UTC");
        when(audits.page(eq(tenantId), eq(outletId), any(), any(), any(), any(), any(), eq(0), eq(1)))
                .thenReturn(List.of());
        when(audits.count(eq(tenantId), eq(outletId), any(), any(), any(), any(), any())).thenReturn(0L);

        service.list(owner, outletId, filter);

        verify(audits).page(eq(tenantId), eq(outletId), eq(Instant.parse("2024-01-01T00:00:00Z")),
                eq(Instant.parse("2025-01-01T00:00:00Z")), eq(null), eq(null), eq(null), eq(0), eq(1));
        verify(audits).count(tenantId, outletId, Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2025-01-01T00:00:00Z"), null, null, null);
    }

    private void allowOwner(String timeZone) {
        TenantEntity tenant = new TenantEntity();
        OutletEntity outlet = OutletEntity.open(tenant, "Outlet", timeZone, null, null);
        when(guard.requireOwner(owner, outletId)).thenReturn(new MerchantAccessContext(tenant, outlet,
                TenantMembershipEntity.grantOwner(null, tenant)));
    }

    private static MerchantAuditFilter filter(LocalDate start, LocalDate end, Integer page, Integer size) {
        MerchantAuditFilter filter = new MerchantAuditFilter();
        filter.setStartDate(start);
        filter.setEndDate(end);
        filter.setPage(page);
        filter.setSize(size);
        return filter;
    }

    private MerchantAuditRepository.AuditRow row() {
        return new MerchantAuditRepository.AuditRow(UUID.randomUUID(), Instant.parse("2026-01-05T10:00:00Z"),
                "ACTION", "ENTITY", UUID.randomUUID(), actorId, "Owner", null, null);
    }
}
