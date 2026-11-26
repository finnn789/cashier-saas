package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.response.MerchantSubscriptionResponse;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformInvoiceEntity;
import org.lite.project.cashiersaas.entity.shared.billing.SubscriptionEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.repository.AiUsageRepository;
import org.lite.project.cashiersaas.repository.PlatformBillingRepository;
import org.lite.project.cashiersaas.repository.SubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MerchantSubscriptionService {
    private final MerchantAccessGuard merchantAccessGuard;
    private final PlanEntitlementService planEntitlementService;
    private final SubscriptionRepository subscriptions;
    private final AiUsageRepository aiUsage;
    private final PlatformBillingRepository billing;
    private final MockPaymentGateway gateway;

    @Transactional(readOnly = true)
    public MerchantSubscriptionResponse get(TenantPrincipal principal) {
        TenantEntity tenant = merchantAccessGuard.requireOwnerIgnoringAllowance(principal, principal.getOutletId()).getTenant();
        UUID tenantId = tenant.getId();
        Instant now = Instant.now();
        PlanCode plan = planEntitlementService.effectivePlan(tenantId, now);

        MerchantSubscriptionResponse response = new MerchantSubscriptionResponse();
        response.setPlanCode(plan);
        response.setActiveUntil(activeUntil(tenantId, plan, now));
        response.setTrial(trial(tenant, plan, now));
        response.setOutlets(outlets(tenantId, now));
        if (plan == PlanCode.PREMIUM) {
            response.setAi(ai(tenant, tenantId, now));
        }
        response.setPendingInvoiceId(billing.openInvoice(tenantId, now).map(PlatformInvoiceEntity::getId).orElse(null));
        response.setMockPaymentEnabled(gateway.isEnabled());
        if (plan != PlanCode.FREETIER) {
            response.setOutletAddOnMonthlyPrice(MembershipPlanCatalogService.OUTLET_ADDON_MONTHLY);
        }
        return response;
    }

    /** End of the active paid row that gives the plan; null when open-ended or when the plan comes from the trial or the free tier. */
    private Instant activeUntil(UUID tenantId, PlanCode plan, Instant now) {
        List<SubscriptionEntity> rows = subscriptions.findActiveAt(tenantId, now).stream()
                .filter(row -> row.getPlan() == plan && plan != PlanCode.FREETIER).toList();
        if (rows.isEmpty() || rows.stream().anyMatch(row -> row.getEndsAt() == null)) {
            return null;
        }
        return rows.stream().map(SubscriptionEntity::getEndsAt).max(Instant::compareTo).orElse(null);
    }

    private static MerchantSubscriptionResponse.Trial trial(TenantEntity tenant, PlanCode plan, Instant now) {
        Instant started = tenant.getPremiumTrialStartedAt();
        MerchantSubscriptionResponse.Trial trial = new MerchantSubscriptionResponse.Trial();
        trial.setActive(tenant.isPremiumTrialActiveAt(now));
        trial.setStartedAt(started);
        trial.setEndsAt(started == null ? null : started.plus(TenantEntity.PREMIUM_TRIAL_DURATION));
        trial.setEligible(started == null && plan == PlanCode.FREETIER);
        return trial;
    }

    private MerchantSubscriptionResponse.Outlets outlets(UUID tenantId, Instant now) {
        int allowed = planEntitlementService.allowedOutletCount(tenantId, now);
        MerchantSubscriptionResponse.Outlets outlets = new MerchantSubscriptionResponse.Outlets();
        outlets.setAllowed(allowed);
        outlets.setActive(planEntitlementService.activeOutletCount(tenantId));
        outlets.setExtra(allowed - 1);
        return outlets;
    }

    private MerchantSubscriptionResponse.Ai ai(TenantEntity tenant, UUID tenantId, Instant now) {
        ZoneId zone = ZoneId.of(tenant.getTimeZone());
        LocalDate today = LocalDate.now(zone);
        LocalDate periodMonth = today.withDayOfMonth(1);
        AiUsageRepository.Usage usage = aiUsage.readUsage(tenantId, periodMonth, today);
        MerchantSubscriptionResponse.Ai ai = new MerchantSubscriptionResponse.Ai();
        ai.setMonthlyLimit(AiUsageRepository.MONTHLY_QUESTIONS + usage.getBonusQuestions());
        ai.setMonthlyUsed(usage.getQuestionsUsed());
        ai.setDailyCap(AiUsageRepository.DAILY_QUESTION_CAP);
        ai.setDailyUsed(usage.getDayQuestions());
        ai.setResetsAt(periodMonth.plusMonths(1).atStartOfDay(zone).toInstant());
        return ai;
    }
}
