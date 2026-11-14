package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.FnbPaymentLabelMapper;
import org.lite.project.cashiersaas.dto.request.FnbPaymentLabelRequest;
import org.lite.project.cashiersaas.dto.response.FnbPaymentLabelResponse;
import org.lite.project.cashiersaas.entity.fnb.PaymentLabelEntity;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.repository.FnbPaymentLabelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FnbPaymentLabelService {
    private static final int MAX_LABELS = 20;
    private static final String[] DEFAULT_LABELS = {"QRIS", "Transfer", "Debit"};
    private final MerchantAccessGuard guard;
    private final FnbPaymentLabelRepository labels;
    private final FnbOrderRepository orders;
    private final AuditLogRepository audit;

    @Transactional
    public FnbPaymentLabelResponse create(TenantPrincipal principal, UUID outletId, FnbPaymentLabelRequest request) {
        orders.lockTenant(principal.getTenantId());
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        if (labels.count(principal.getTenantId(), outletId) >= MAX_LABELS)
            throw new OperationConflictException("Jumlah label maksimal " + MAX_LABELS + ".");
        if (labels.existsName(principal.getTenantId(), outletId, request.getName(), null))
            throw new OperationConflictException("Label sudah ada.");
        int order = request.getDisplayOrder() == null ? (int) labels.count(principal.getTenantId(), outletId) + 1 : request.getDisplayOrder();
        PaymentLabelEntity label = PaymentLabelEntity.create(context.getTenant(), context.getOutlet(), request.getName(), order);
        labels.persist(label);
        record(context, label);
        return FnbPaymentLabelMapper.label(label);
    }

    @Transactional
    public FnbPaymentLabelResponse update(TenantPrincipal principal, UUID outletId, UUID labelId, FnbPaymentLabelRequest request) {
        orders.lockTenant(principal.getTenantId());
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        PaymentLabelEntity label = labels.byId(principal.getTenantId(), outletId, labelId).orElseThrow(ResourceNotFoundException::new);
        if (labels.existsName(principal.getTenantId(), outletId, request.getName(), labelId))
            throw new OperationConflictException("Label sudah ada.");
        label.update(request.getName(),
                request.getDisplayOrder() == null ? label.getDisplayOrder() : request.getDisplayOrder(),
                request.getActive() == null ? label.isActive() : request.getActive());
        record(context, label);
        return FnbPaymentLabelMapper.label(label);
    }

    @Transactional(readOnly = true)
    public List<FnbPaymentLabelResponse> list(TenantPrincipal principal, UUID outletId, boolean activeOnly) {
        if (activeOnly) guard.requireOutlet(principal, outletId); else guard.requireOwner(principal, outletId);
        return labels.list(principal.getTenantId(), outletId, activeOnly).stream().map(FnbPaymentLabelMapper::label).toList();
    }

    /** Called once when the first outlet is created; later outlets start without labels until the owner adds them. */
    public void createDefaults(TenantEntity tenant, OutletEntity outlet) {
        for (int i = 0; i < DEFAULT_LABELS.length; i++) labels.persist(PaymentLabelEntity.create(tenant, outlet, DEFAULT_LABELS[i], i + 1));
    }

    private void record(MerchantAccessContext context, PaymentLabelEntity label) {
        audit.persist(AuditLogEntity.recordAction(context.getTenant(), context.getOutlet(), context.getMembership().getUser(),
                AuditLogEntity.Action.PAYMENT_LABEL_CHANGED, label.getId(), null, "name=" + label.getName() + " active=" + label.isActive()));
    }
}
