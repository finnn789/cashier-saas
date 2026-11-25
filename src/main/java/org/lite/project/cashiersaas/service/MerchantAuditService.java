package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.MerchantAuditMapper;
import org.lite.project.cashiersaas.dto.request.MerchantAuditFilter;
import org.lite.project.cashiersaas.dto.response.MerchantAuditPageResponse;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.repository.MerchantAuditRepository;
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
public class MerchantAuditService {
    private static final int MAX_INCLUSIVE_DAYS = 366;
    private static final int MAX_PAGE = 1_000_000;
    private static final int MAX_SIZE = 100;
    private static final int MAX_FILTER_TEXT_LENGTH = 80;
    private final MerchantAccessGuard guard;
    private final MerchantAuditRepository audits;
    private final PlanEntitlementService planEntitlementService;

    @Transactional(readOnly = true)
    public MerchantAuditPageResponse list(TenantPrincipal principal, UUID outletId, MerchantAuditFilter filter) {
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        planEntitlementService.require(principal.getTenantId(), PlanCode.PRO);
        Query query = query(context, filter);
        List<MerchantAuditRepository.AuditRow> rows = audits.page(principal.getTenantId(), outletId, query.start,
                query.end, filter.getAction(), filter.getActorId(), filter.getEntityType(), query.page, query.size);
        boolean hasNext = rows.size() > query.size;
        if (hasNext) {
            rows = rows.subList(0, query.size);
        }
        MerchantAuditPageResponse response = new MerchantAuditPageResponse();
        response.setStartDate(query.startDate);
        response.setEndDate(query.endDate);
        response.setTimeZone(query.zone.getId());
        response.setEntries(rows.stream().map(row -> MerchantAuditMapper.entry(row, query.zone)).toList());
        response.setPage(query.page);
        response.setSize(query.size);
        response.setTotal(audits.count(principal.getTenantId(), outletId, query.start, query.end, filter.getAction(),
                filter.getActorId(), filter.getEntityType()));
        response.setHasNext(hasNext);
        return response;
    }

    private Query query(MerchantAccessContext context, MerchantAuditFilter filter) {
        if (isTooLong(filter.getAction()) || isTooLong(filter.getEntityType())) {
            throw new InvalidOperationException("Filter audit melebihi panjang maksimum.");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(context.getOutlet().getTimeZone());
        } catch (DateTimeException exception) {
            throw new InvalidOperationException("Zona waktu outlet tidak valid.");
        }
        LocalDate today = LocalDate.now(zone);
        LocalDate startDate = filter.getStartDate() == null ? today : filter.getStartDate();
        LocalDate endDate = filter.getEndDate() == null ? today : filter.getEndDate();
        if (endDate.isBefore(startDate)
                || ChronoUnit.DAYS.between(startDate, endDate) > MAX_INCLUSIVE_DAYS - 1L) {
            throw new InvalidOperationException("Rentang tanggal audit maksimal 366 hari.");
        }
        int page = filter.getPage() == null ? 0 : filter.getPage();
        int size = filter.getSize() == null ? 20 : filter.getSize();
        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_SIZE) {
            throw new InvalidOperationException("Parameter halaman tidak valid.");
        }
        try {
            Instant start = startDate.atStartOfDay(zone).toInstant();
            Instant end = endDate.plusDays(1).atStartOfDay(zone).toInstant();
            return new Query(zone, startDate, endDate, start, end, page, size);
        } catch (DateTimeException exception) {
            throw new InvalidOperationException("Rentang tanggal audit tidak valid.");
        }
    }

    private boolean isTooLong(String value) {
        return value != null && value.length() > MAX_FILTER_TEXT_LENGTH;
    }

    private static class Query {
        private final ZoneId zone;
        private final LocalDate startDate;
        private final LocalDate endDate;
        private final Instant start;
        private final Instant end;
        private final int page;
        private final int size;

        private Query(ZoneId zone, LocalDate startDate, LocalDate endDate, Instant start, Instant end, int page,
                      int size) {
            this.zone = zone;
            this.startDate = startDate;
            this.endDate = endDate;
            this.start = start;
            this.end = end;
            this.page = page;
            this.size = size;
        }
    }
}
