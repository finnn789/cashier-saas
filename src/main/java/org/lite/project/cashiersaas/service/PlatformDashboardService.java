package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.PlatformPrincipal;
import org.lite.project.cashiersaas.dto.PlatformAdminMapper;
import org.lite.project.cashiersaas.dto.PlatformInvoiceMapper;
import org.lite.project.cashiersaas.dto.response.platform.PlatformAiUsageResponse;
import org.lite.project.cashiersaas.dto.response.platform.PlatformAuditItemResponse;
import org.lite.project.cashiersaas.dto.response.platform.PlatformInvoiceRowResponse;
import org.lite.project.cashiersaas.dto.response.platform.PlatformOverviewResponse;
import org.lite.project.cashiersaas.dto.response.platform.PlatformPageResponse;
import org.lite.project.cashiersaas.dto.response.platform.PlatformTenantDetailResponse;
import org.lite.project.cashiersaas.dto.response.platform.PlatformTenantItemResponse;
import org.lite.project.cashiersaas.dto.response.PlatformInvoiceResponse;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformInvoiceEntity;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformPaymentAttemptEntity;
import org.lite.project.cashiersaas.entity.shared.billing.SubscriptionEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AiUsageRepository;
import org.lite.project.cashiersaas.repository.PlatformAdminRepository;
import org.lite.project.cashiersaas.repository.PlatformBillingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read side of the platform (SUPERADMIN) panel. Every public method starts with the access guard. */
@Service
@RequiredArgsConstructor
public class PlatformDashboardService {
    /** Revenue months follow the platform's own calendar, not a tenant's. */
    private static final ZoneId PLATFORM_ZONE = ZoneId.of("Asia/Jakarta");
    private static final int MAX_PAGE_SIZE = 50;
    private static final int DETAIL_INVOICES = 20;
    private static final int DETAIL_SUBSCRIPTIONS = 50;
    private static final Set<String> PLAN_FILTERS = Set.of("FREETIER", "PRO", "PREMIUM", "TRIAL");

    private final PlatformAccessGuard guard;
    private final PlatformAdminRepository admin;
    private final PlatformBillingRepository billing;
    private final AiUsageRepository aiUsage;
    private final MockPaymentGateway gateway;

    @Transactional(readOnly = true)
    public PlatformOverviewResponse overview(PlatformPrincipal principal) {
        guard.require(principal);
        Instant now = Instant.now();
        Instant trialCutoff = now.minus(TenantEntity.PREMIUM_TRIAL_DURATION);
        PlatformOverviewResponse response = new PlatformOverviewResponse();
        response.setTenantsTotal(admin.countTenants(null, null, null, now, trialCutoff));
        Map<String, Long> byPlan = new LinkedHashMap<>();
        for (String plan : List.of("FREETIER", "PRO", "PREMIUM", "TRIAL")) {
            byPlan.put(plan, admin.countTenants(null, plan, null, now, trialCutoff));
        }
        response.setTenantsByPlan(byPlan);
        response.setSuspendedTenants(admin.countTenants(null, null, TenantEntity.SUSPENDED, now, trialCutoff));
        response.setIssuedInvoices(admin.countOpenInvoices(now));
        LocalDate monthStart = LocalDate.now(PLATFORM_ZONE).withDayOfMonth(1);
        PlatformAdminRepository.Revenue revenue = admin.paidBetween(monthStart.atStartOfDay(PLATFORM_ZONE).toInstant(),
                monthStart.plusMonths(1).atStartOfDay(PLATFORM_ZONE).toInstant());
        response.setPaidThisMonthCount(revenue.getCount());
        response.setPaidThisMonthAmount(revenue.getAmount());
        response.setExpiringIn7Days(admin.countExpiring(now, now.plusSeconds(7 * 24 * 3600L)));
        return response;
    }

    @Transactional(readOnly = true)
    public PlatformPageResponse<PlatformTenantItemResponse> tenants(PlatformPrincipal principal, String q, String plan, String status,
                                                                    int page, int size) {
        guard.require(principal);
        String planFilter = blankToNull(plan) == null ? null : plan.trim().toUpperCase(Locale.ROOT);
        if (planFilter != null && !PLAN_FILTERS.contains(planFilter)) {
            throw new InvalidOperationException("Filter paket tidak valid.");
        }
        String statusFilter = blankToNull(status) == null ? null : status.trim().toUpperCase(Locale.ROOT);
        String search = blankToNull(q);
        int safePage = Math.max(page, 0);
        int safeSize = clampSize(size);
        Instant now = Instant.now();
        Instant trialCutoff = now.minus(TenantEntity.PREMIUM_TRIAL_DURATION);
        List<TenantEntity> tenants = admin.tenantPage(search, planFilter, statusFilter, now, trialCutoff, safePage, safeSize);
        List<UUID> ids = tenants.stream().map(TenantEntity::getId).toList();
        Map<UUID, List<SubscriptionEntity>> rows = admin.activeRows(ids, now);
        Map<UUID, Integer> outlets = admin.activeOutletCounts(ids);
        PlatformPageResponse<PlatformTenantItemResponse> response = new PlatformPageResponse<>();
        response.setItems(tenants.stream().map(tenant -> item(tenant, rows.getOrDefault(tenant.getId(), List.of()),
                outlets.getOrDefault(tenant.getId(), 0), now)).toList());
        response.setPage(safePage);
        response.setSize(safeSize);
        response.setTotal(admin.countTenants(search, planFilter, statusFilter, now, trialCutoff));
        return response;
    }

    @Transactional(readOnly = true)
    public PlatformTenantDetailResponse tenant(PlatformPrincipal principal, UUID tenantId) {
        guard.require(principal);
        return detail(tenantId);
    }

    @Transactional(readOnly = true)
    public PlatformPageResponse<PlatformInvoiceRowResponse> invoices(PlatformPrincipal principal, String status, String q,
                                                                      int page, int size) {
        guard.require(principal);
        String statusFilter = blankToNull(status) == null ? null : status.trim().toUpperCase(Locale.ROOT);
        String search = blankToNull(q);
        int safePage = Math.max(page, 0);
        int safeSize = clampSize(size);
        List<PlatformInvoiceEntity> invoices = admin.invoicePage(statusFilter, search, safePage, safeSize);
        Map<UUID, PlatformPaymentAttemptEntity> attempts = billing.latestAttempts(invoices.stream().map(PlatformInvoiceEntity::getId).toList());
        PlatformPageResponse<PlatformInvoiceRowResponse> response = new PlatformPageResponse<>();
        response.setItems(invoices.stream()
                .map(invoice -> PlatformAdminMapper.toInvoiceRow(invoice, attempts.get(invoice.getId()), gateway.isEnabled())).toList());
        response.setPage(safePage);
        response.setSize(safeSize);
        response.setTotal(admin.countInvoices(statusFilter, search));
        return response;
    }

    @Transactional(readOnly = true)
    public PlatformPageResponse<PlatformAuditItemResponse> audit(PlatformPrincipal principal, UUID tenantId, int page, int size) {
        guard.require(principal);
        int safePage = Math.max(page, 0);
        int safeSize = clampSize(size);
        PlatformPageResponse<PlatformAuditItemResponse> response = new PlatformPageResponse<>();
        response.setItems(admin.auditPage(tenantId, safePage, safeSize).stream().map(PlatformAdminMapper::toAuditItem).toList());
        response.setPage(safePage);
        response.setSize(safeSize);
        response.setTotal(admin.countAudit(tenantId));
        return response;
    }

    private PlatformTenantDetailResponse detail(UUID tenantId) {
        TenantEntity tenant = admin.tenant(tenantId).orElseThrow(ResourceNotFoundException::new);
        Instant now = Instant.now();
        List<SubscriptionEntity> active = admin.activeRows(List.of(tenantId), now).getOrDefault(tenantId, List.of());
        PlanInfo info = planInfo(tenant, active, now);
        List<PlatformInvoiceEntity> invoices = billing.recent(tenantId, DETAIL_INVOICES);
        Map<UUID, PlatformPaymentAttemptEntity> attempts = billing.latestAttempts(invoices.stream().map(PlatformInvoiceEntity::getId).toList());
        List<PlatformInvoiceResponse> invoiceResponses = invoices.stream()
                .map(invoice -> PlatformInvoiceMapper.toResponse(invoice, attempts.get(invoice.getId()), gateway.isEnabled())).toList();
        LocalDate today = LocalDate.now(ZoneId.of(tenant.getTimeZone()));
        PlatformAiUsageResponse ai = PlatformAdminMapper.toAiUsage(aiUsage.readUsage(tenantId, today.withDayOfMonth(1), today));
        return PlatformAdminMapper.toTenantDetail(tenant, info.plan, info.activeUntil, info.trialActive,
                admin.activeOutletCounts(List.of(tenantId)).getOrDefault(tenantId, 0), info.outletsAllowed,
                admin.subscriptions(tenantId, DETAIL_SUBSCRIPTIONS).stream().map(PlatformAdminMapper::toSubscription).toList(),
                invoiceResponses, ai);
    }

    private PlatformTenantItemResponse item(TenantEntity tenant, List<SubscriptionEntity> active, int outletsActive, Instant now) {
        PlanInfo info = planInfo(tenant, active, now);
        return PlatformAdminMapper.toTenantItem(tenant, info.plan, info.activeUntil, info.trialActive, outletsActive, info.outletsAllowed);
    }

    /**
     * Same rules as PlanEntitlementService (highest active row, running trial counts as PREMIUM, 1 + extra outlets of paid rows),
     * computed from rows loaded for a whole page: the per-tenant service would query once per row.
     */
    private PlanInfo planInfo(TenantEntity tenant, List<SubscriptionEntity> active, Instant now) {
        PlanCode paidTop = active.stream().map(SubscriptionEntity::getPlan).max(Comparator.naturalOrder()).orElse(PlanCode.FREETIER);
        boolean trial = tenant.isPremiumTrialActiveAt(now);
        PlanCode plan = trial ? PlanCode.PREMIUM : paidTop;
        Instant activeUntil = null;
        if (plan != PlanCode.FREETIER) {
            if (paidTop == plan) {
                List<SubscriptionEntity> topRows = active.stream().filter(row -> row.getPlan() == plan).toList();
                activeUntil = topRows.stream().anyMatch(row -> row.getEndsAt() == null) ? null
                        : topRows.stream().map(SubscriptionEntity::getEndsAt).max(Comparator.naturalOrder()).orElse(null);
            } else {
                activeUntil = tenant.getPremiumTrialStartedAt().plus(TenantEntity.PREMIUM_TRIAL_DURATION);
            }
        }
        int allowed = 1 + active.stream().filter(row -> row.getPlan() != PlanCode.FREETIER).mapToInt(SubscriptionEntity::getExtraOutlets).sum();
        return new PlanInfo(plan, activeUntil, trial, allowed);
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static final class PlanInfo {
        private final PlanCode plan;
        private final Instant activeUntil;
        private final boolean trialActive;
        private final int outletsAllowed;

        private PlanInfo(PlanCode plan, Instant activeUntil, boolean trialActive, int outletsAllowed) {
            this.plan = plan;
            this.activeUntil = activeUntil;
            this.trialActive = trialActive;
            this.outletsAllowed = outletsAllowed;
        }
    }
}
