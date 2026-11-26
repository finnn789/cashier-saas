package org.lite.project.cashiersaas.dto;

import org.lite.project.cashiersaas.dto.response.PlatformInvoiceResponse;
import org.lite.project.cashiersaas.dto.response.platform.PlatformAiUsageResponse;
import org.lite.project.cashiersaas.dto.response.platform.PlatformAuditItemResponse;
import org.lite.project.cashiersaas.dto.response.platform.PlatformInvoiceRowResponse;
import org.lite.project.cashiersaas.dto.response.platform.PlatformSubscriptionResponse;
import org.lite.project.cashiersaas.dto.response.platform.PlatformTenantDetailResponse;
import org.lite.project.cashiersaas.dto.response.platform.PlatformTenantItemResponse;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformInvoiceEntity;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformPaymentAttemptEntity;
import org.lite.project.cashiersaas.entity.shared.billing.SubscriptionEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.repository.AiUsageRepository;
import org.springframework.beans.BeanUtils;

import java.time.Instant;
import java.util.List;

public final class PlatformAdminMapper {
    private PlatformAdminMapper() { }

    public static PlatformTenantItemResponse toTenantItem(TenantEntity tenant, PlanCode effectivePlan, Instant activeUntil,
                                                          boolean trialActive, int outletsActive, int outletsAllowed) {
        PlatformTenantItemResponse item = new PlatformTenantItemResponse();
        fill(item, tenant, effectivePlan, activeUntil, trialActive, outletsActive, outletsAllowed);
        return item;
    }

    public static PlatformTenantDetailResponse toTenantDetail(TenantEntity tenant, PlanCode effectivePlan, Instant activeUntil,
                                                              boolean trialActive, int outletsActive, int outletsAllowed,
                                                              List<PlatformSubscriptionResponse> subscriptions,
                                                              List<PlatformInvoiceResponse> invoices, PlatformAiUsageResponse ai) {
        PlatformTenantDetailResponse detail = new PlatformTenantDetailResponse();
        fill(detail, tenant, effectivePlan, activeUntil, trialActive, outletsActive, outletsAllowed);
        detail.setSubscriptions(subscriptions);
        detail.setInvoices(invoices);
        detail.setAi(ai);
        return detail;
    }

    public static PlatformSubscriptionResponse toSubscription(SubscriptionEntity row) {
        PlatformSubscriptionResponse response = new PlatformSubscriptionResponse();
        response.setId(row.getId());
        response.setPlanCode(row.getPlan());
        response.setStatus(row.getStatus());
        response.setStartsAt(row.getStartsAt());
        response.setEndsAt(row.getEndsAt());
        response.setExtraOutlets(row.getExtraOutlets());
        return response;
    }

    public static PlatformAiUsageResponse toAiUsage(AiUsageRepository.Usage usage) {
        PlatformAiUsageResponse response = new PlatformAiUsageResponse();
        response.setMonthlyLimit(AiUsageRepository.MONTHLY_QUESTIONS + usage.getBonusQuestions());
        response.setMonthlyUsed(usage.getQuestionsUsed());
        response.setDailyUsed(usage.getDayQuestions());
        return response;
    }

    /** The owner-facing invoice shape plus the tenant; {@code latestAttempt} may be null. */
    public static PlatformInvoiceRowResponse toInvoiceRow(PlatformInvoiceEntity invoice, PlatformPaymentAttemptEntity latestAttempt,
                                                          boolean mockPaymentEnabled) {
        PlatformInvoiceRowResponse row = new PlatformInvoiceRowResponse();
        BeanUtils.copyProperties(PlatformInvoiceMapper.toResponse(invoice, latestAttempt, mockPaymentEnabled), row);
        row.setTenantId(invoice.getTenant().getId());
        row.setTenantName(invoice.getTenant().getName());
        return row;
    }

    public static PlatformAuditItemResponse toAuditItem(AuditLogEntity log) {
        PlatformAuditItemResponse item = new PlatformAuditItemResponse();
        item.setId(log.getId());
        item.setTenantId(log.getTenant().getId());
        item.setTenantName(log.getTenant().getName());
        item.setActorName(log.getActor() == null ? null : log.getActor().getFullName());
        item.setAction(log.getAction());
        item.setEntityType(log.getEntityType());
        item.setEntityId(log.getEntityId());
        item.setReason(log.getReason());
        item.setDetails(log.getDetails());
        item.setCreatedAt(log.getCreatedAt());
        return item;
    }

    private static void fill(PlatformTenantItemResponse target, TenantEntity tenant, PlanCode effectivePlan, Instant activeUntil,
                             boolean trialActive, int outletsActive, int outletsAllowed) {
        StaffUserEntity owner = tenant.getCreatedByUser();
        target.setId(tenant.getId());
        target.setName(tenant.getName());
        target.setOwnerName(owner == null ? null : owner.getFullName());
        target.setOwnerEmail(owner == null ? null : owner.getEmail());
        target.setPhoneNumber(tenant.getPhoneNumber());
        target.setStatus(tenant.getStatus());
        target.setEffectivePlan(effectivePlan);
        target.setActiveUntil(activeUntil);
        target.setTrialActive(trialActive);
        target.setOutletsActive(outletsActive);
        target.setOutletsAllowed(outletsAllowed);
        target.setCreatedAt(tenant.getCreatedAt());
    }
}
