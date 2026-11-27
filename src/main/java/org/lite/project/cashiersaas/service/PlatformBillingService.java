package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.PlatformInvoiceMapper;
import org.lite.project.cashiersaas.dto.request.InvoiceCancelRequest;
import org.lite.project.cashiersaas.dto.request.MockPaymentRequest;
import org.lite.project.cashiersaas.dto.request.SubscriptionCheckoutRequest;
import org.lite.project.cashiersaas.dto.response.PlatformInvoiceResponse;
import org.lite.project.cashiersaas.dto.response.SubscriptionQuoteResponse;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformInvoiceEntity;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformPaymentAttemptEntity;
import org.lite.project.cashiersaas.entity.shared.billing.SubscriptionEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.BillingCycle;
import org.lite.project.cashiersaas.enums.InvoiceKind;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.repository.PlatformBillingRepository;
import org.lite.project.cashiersaas.repository.SubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owner self-service billing: price quote, invoice checkout, invoice history and cancellation. Payment itself is in the settlement service. */
@Service
@RequiredArgsConstructor
public class PlatformBillingService {
    private static final int MAX_EXTRA_OUTLETS = 20;
    private static final int HISTORY_LIMIT = 50;
    private static final String NOT_CANCELLABLE = "Tagihan tidak dapat dibatalkan.";

    private final MerchantAccessGuard guard;
    private final FnbOrderRepository orders;
    private final PlatformBillingRepository billing;
    private final SubscriptionRepository subscriptions;
    private final AuditLogRepository audit;
    private final DigestService digests;
    private final MembershipPlanCatalogService catalog;
    private final PlatformBillingSettlementService settlement;
    private final PaymentGateway gateway;

    @Transactional(readOnly = true)
    public SubscriptionQuoteResponse quote(TenantPrincipal principal, SubscriptionCheckoutRequest request) {
        return buildQuote(owner(principal), request, Instant.now());
    }

    @Transactional
    public PlatformInvoiceResponse checkout(TenantPrincipal principal, String key, SubscriptionCheckoutRequest request) {
        TenantEntity tenant = owner(principal);
        UUID tenantId = tenant.getId();
        orders.lockTenant(tenantId);
        String idempotencyKey = FnbPosOrderService.validatedKey(key);
        String fingerprint = fingerprint(request);
        Instant now = Instant.now();
        PlatformPaymentAttemptEntity keyed = billing.attemptByKey(tenantId, idempotencyKey).orElse(null);
        if (keyed != null) {
            if (!keyed.getRequestFingerprint().equals(fingerprint)) {
                throw new OperationConflictException("Idempotency-Key telah digunakan untuk tagihan berbeda.");
            }
            settlement.expireDue(tenantId, now);
            return response(keyed.getPlatformInvoice());
        }
        settlement.expireDue(tenantId, now);
        if (billing.openInvoice(tenantId, now).isPresent()) {
            throw new OperationConflictException("Masih ada tagihan yang belum dibayar.");
        }
        SubscriptionQuoteResponse quote = buildQuote(tenant, request, now);
        SubscriptionEntity subscription;
        if (quote.getKind() == InvoiceKind.SUBSCRIPTION) {
            subscription = SubscriptionEntity.pending(tenant, quote.getPlanCode(), quote.getExtraOutlets());
            subscriptions.persist(subscription);
        } else {
            subscription = settlement.requirePaidPremium(tenantId, now);
        }
        PlatformInvoiceEntity invoice = PlatformInvoiceEntity.issue(tenant, subscription, invoiceNumber(tenant, now), quote.getKind(),
                quote.getPlanCode().name(), quote.getBillingCycle(), quote.getPeriodMonths(), quote.getExtraOutlets(),
                quote.getAiQuestions(), quote.getTotalAmount(), now);
        billing.persist(invoice);
        PlatformPaymentAttemptEntity attempt = newAttempt(principal, invoice, idempotencyKey, fingerprint);
        audit.persist(AuditLogEntity.recordAction(tenant, null, billing.actor(principal.getUserId()),
                AuditLogEntity.Action.PLATFORM_INVOICE_ISSUED, invoice.getId(), null,
                "invoice=" + invoice.getInvoiceNumber() + " kind=" + invoice.getKind() + " amount=" + invoice.getAmountDue().toPlainString()));
        return PlatformInvoiceMapper.toResponse(invoice, attempt, gateway.isMockEnabled());
    }

    /** Lazy expiry writes, so this is not read-only. */
    @Transactional
    public List<PlatformInvoiceResponse> list(TenantPrincipal principal) {
        UUID tenantId = owner(principal).getId();
        settlement.expireDue(tenantId, Instant.now());
        List<PlatformInvoiceEntity> invoices = billing.recent(tenantId, HISTORY_LIMIT);
        Map<UUID, PlatformPaymentAttemptEntity> attempts = billing.latestAttempts(invoices.stream().map(PlatformInvoiceEntity::getId).toList());
        return invoices.stream()
                .map(invoice -> PlatformInvoiceMapper.toResponse(invoice, attempts.get(invoice.getId()), gateway.isMockEnabled())).toList();
    }

    @Transactional
    public PlatformInvoiceResponse get(TenantPrincipal principal, UUID invoiceId) {
        UUID tenantId = owner(principal).getId();
        settlement.expireDue(tenantId, Instant.now());
        return response(billing.invoice(tenantId, invoiceId).orElseThrow(ResourceNotFoundException::new));
    }

    /** Simulated payment: SUCCEEDED settles the invoice, FAILED records a failed attempt. 404 when the mock gateway is off. */
    @Transactional(noRollbackFor = OperationConflictException.class)
    public PlatformInvoiceResponse mockPay(TenantPrincipal principal, UUID invoiceId, MockPaymentRequest request) {
        UUID tenantId = owner(principal).getId();
        if (!gateway.isMockEnabled()) {
            throw new ResourceNotFoundException();
        }
        billing.invoice(tenantId, invoiceId).orElseThrow(ResourceNotFoundException::new);
        PlatformInvoiceEntity invoice = PlatformPaymentAttemptEntity.SUCCEEDED.equals(request.getOutcome())
                ? settlement.settle(invoiceId, null, principal.getUserId(), null)
                : settlement.fail(invoiceId, principal.getUserId());
        return response(invoice);
    }

    /**
     * Midtrans only: the payment page of an unpaid invoice. Reuses the open attempt, or starts a new one after the previous
     * attempt failed. A Snap failure rolls the call back (503).
     */
    @Transactional
    public PlatformInvoiceResponse pay(TenantPrincipal principal, UUID invoiceId) {
        UUID tenantId = owner(principal).getId();
        if (!gateway.isMidtrans()) {
            throw new ResourceNotFoundException();
        }
        orders.lockTenant(tenantId);
        settlement.expireDue(tenantId, Instant.now());
        PlatformInvoiceEntity invoice = billing.invoice(tenantId, invoiceId).orElseThrow(ResourceNotFoundException::new);
        billing.refresh(invoice);
        if (!invoice.isIssued()) {
            throw new OperationConflictException("Tagihan tidak dapat dibayar.");
        }
        PlatformPaymentAttemptEntity attempt = billing.pendingAttempts(invoiceId).stream()
                .filter(open -> PaymentGateway.MIDTRANS_PROVIDER.equals(open.getProviderCode())).findFirst()
                .orElseGet(() -> {
                    String key = "gw-" + UUID.randomUUID();
                    return newAttempt(principal, invoice, key, digests.digest(invoiceId + "|" + key));
                });
        return PlatformInvoiceMapper.toResponse(invoice, attempt, gateway.isMockEnabled());
    }

    @Transactional(noRollbackFor = OperationConflictException.class)
    public PlatformInvoiceResponse cancelForOwner(TenantPrincipal principal, UUID invoiceId, InvoiceCancelRequest request) {
        owner(principal);
        return cancel(principal.getTenantId(), invoiceId, request == null ? null : request.getReason(), principal.getUserId());
    }

    /** Cancels an unpaid invoice of the tenant; also the entry point for an operator acting on a tenant's invoice. */
    @Transactional(noRollbackFor = OperationConflictException.class)
    public PlatformInvoiceResponse cancel(UUID tenantId, UUID invoiceId, String reason, UUID actorUserId) {
        orders.lockTenant(tenantId);
        PlatformInvoiceEntity invoice = billing.invoice(tenantId, invoiceId).orElseThrow(ResourceNotFoundException::new);
        billing.refresh(invoice);
        Instant now = Instant.now();
        if (settlement.expireIfDue(invoice, now) || !invoice.isIssued()) {
            throw new OperationConflictException(NOT_CANCELLABLE);
        }
        String cleanReason = reason == null || reason.isBlank() ? null : reason.trim();
        invoice.cancel(cleanReason, now);
        settlement.releaseHold(invoice);
        audit.persist(AuditLogEntity.recordAction(invoice.getTenant(), null, billing.actor(actorUserId),
                AuditLogEntity.Action.PLATFORM_INVOICE_CANCELLED, invoice.getId(), cleanReason,
                "invoice=" + invoice.getInvoiceNumber() + " amount=" + invoice.getAmountDue().toPlainString()));
        return response(invoice);
    }

    private TenantEntity owner(TenantPrincipal principal) {
        return guard.requireOwnerIgnoringAllowance(principal, principal.getOutletId()).getTenant();
    }

    /** Midtrans shows the owner's name and email on the payment page; the other providers need neither. */
    private PlatformPaymentAttemptEntity newAttempt(TenantPrincipal principal, PlatformInvoiceEntity invoice, String key, String fingerprint) {
        StaffUserEntity user = gateway.isMidtrans() ? billing.actor(principal.getUserId()) : null;
        return gateway.newAttempt(invoice, key, fingerprint, user == null ? null : user.getEmail(), user == null ? null : user.getFullName());
    }

    private PlatformInvoiceResponse response(PlatformInvoiceEntity invoice) {
        return PlatformInvoiceMapper.toResponse(invoice,
                billing.latestAttempts(List.of(invoice.getId())).get(invoice.getId()), gateway.isMockEnabled());
    }

    private SubscriptionQuoteResponse buildQuote(TenantEntity tenant, SubscriptionCheckoutRequest request, Instant now) {
        SubscriptionQuoteResponse quote = new SubscriptionQuoteResponse();
        quote.setKind(request.getKind());
        quote.setCurrencyCode("IDR");
        List<SubscriptionQuoteResponse.Line> lines = new ArrayList<>();
        BigDecimal total;
        if (request.getKind() == InvoiceKind.AI_TOPUP) {
            settlement.requirePaidPremium(tenant.getId(), now);
            LocalDate today = LocalDate.now(ZoneId.of(tenant.getTimeZone()));
            quote.setPlanCode(PlanCode.PREMIUM);
            quote.setChangeType("AI_TOPUP");
            quote.setAiQuestions(MembershipPlanCatalogService.AI_TOPUP_QUESTIONS);
            quote.setEstimatedStartsAt(now);
            quote.setEstimatedEndsAt(today.withDayOfMonth(1).plusMonths(1).atStartOfDay(ZoneId.of(tenant.getTimeZone())).toInstant());
            total = MembershipPlanCatalogService.AI_TOPUP_PRICE;
            lines.add(line("Top-up " + MembershipPlanCatalogService.AI_TOPUP_QUESTIONS + " pertanyaan AI", total));
        } else {
            PlanCode plan = request.getPlanCode();
            BillingCycle cycle = request.getBillingCycle();
            if (plan == null || plan == PlanCode.FREETIER) {
                throw new InvalidOperationException("Paket wajib dipilih: Pro atau Premium.");
            }
            if (cycle == null) {
                throw new InvalidOperationException("Siklus tagihan wajib dipilih: bulanan atau tahunan.");
            }
            int extra = request.getExtraOutlets() == null ? 0 : request.getExtraOutlets();
            if (extra < 0 || extra > MAX_EXTRA_OUTLETS) {
                throw new InvalidOperationException("Outlet tambahan harus antara 0 dan " + MAX_EXTRA_OUTLETS + ".");
            }
            int months = cycle.getMonths();
            BigDecimal planPrice = catalog.price(plan, cycle);
            total = planPrice;
            lines.add(line("Paket " + plan.getDisplayName() + " (" + (cycle == BillingCycle.YEARLY ? "Tahunan" : "Bulanan") + ")", planPrice));
            if (extra > 0) {
                BigDecimal addOn = MembershipPlanCatalogService.OUTLET_ADDON_MONTHLY.multiply(BigDecimal.valueOf((long) extra * months));
                total = total.add(addOn);
                lines.add(line("Outlet tambahan (" + extra + " outlet x " + months + " bulan)", addOn));
            }
            Instant startsAt = settlement.subscriptionStart(tenant.getId(), plan, now);
            quote.setPlanCode(plan);
            quote.setBillingCycle(cycle);
            quote.setPeriodMonths(months);
            quote.setExtraOutlets(extra);
            quote.setChangeType(changeType(tenant.getId(), plan, now));
            quote.setEstimatedStartsAt(startsAt);
            quote.setEstimatedEndsAt(settlement.periodEnd(tenant, startsAt, months));
        }
        quote.setLines(lines);
        quote.setTotalAmount(total);
        return quote;
    }

    private String changeType(UUID tenantId, PlanCode plan, Instant now) {
        PlanCode highest = subscriptions.findLivePaid(tenantId, now).stream().map(SubscriptionEntity::getPlan)
                .max(PlanCode::compareTo).orElse(null);
        if (highest == null) {
            return "NEW";
        }
        int order = plan.compareTo(highest);
        return order == 0 ? "RENEW" : order > 0 ? "UPGRADE" : "DOWNGRADE";
    }

    private static SubscriptionQuoteResponse.Line line(String label, BigDecimal amount) {
        SubscriptionQuoteResponse.Line line = new SubscriptionQuoteResponse.Line();
        line.setLabel(label);
        line.setAmount(amount);
        return line;
    }

    /** Same purchase gives the same fingerprint: a top-up has no plan fields. */
    private String fingerprint(SubscriptionCheckoutRequest request) {
        if (request.getKind() == InvoiceKind.AI_TOPUP) {
            return digests.digest("AI_TOPUP");
        }
        return digests.digest("SUBSCRIPTION|" + request.getPlanCode() + "|" + request.getBillingCycle() + "|"
                + (request.getExtraOutlets() == null ? 0 : request.getExtraOutlets()));
    }

    private static String invoiceNumber(TenantEntity tenant, Instant now) {
        String date = now.atZone(ZoneId.of(tenant.getTimeZone())).format(DateTimeFormatter.BASIC_ISO_DATE);
        return "INV-" + date + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }
}
