package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.FnbPaymentLabelRequest;
import org.lite.project.cashiersaas.dto.response.FnbPaymentLabelResponse;
import org.lite.project.cashiersaas.entity.fnb.PaymentLabelEntity;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.repository.FnbPaymentLabelRepository;
import org.lite.project.cashiersaas.service.FnbPaymentLabelService;
import org.lite.project.cashiersaas.service.MerchantAccessGuard;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class FnbPaymentLabelServiceTest {
    @Mock private MerchantAccessGuard guard;
    @Mock private FnbPaymentLabelRepository labels;
    @Mock private FnbOrderRepository orders;
    @Mock private AuditLogRepository audit;
    @InjectMocks private FnbPaymentLabelService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID outletId = UUID.randomUUID();
    private final TenantEntity tenant = withId(new TenantEntity(), tenantId);
    private final OutletEntity outlet = withId(OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null), outletId);
    private final StaffUserEntity owner = StaffUserEntity.createCashier("o@example.com", "hash", "Owner", null);
    private final MerchantAccessContext context =
            new MerchantAccessContext(tenant, outlet, TenantMembershipEntity.grantOwner(owner, tenant));
    private final TenantPrincipal principal = new TenantPrincipal(UUID.randomUUID(), tenantId, Set.of(Role.OWNERMERCHANT));

    private static FnbPaymentLabelRequest request(String name, Integer displayOrder, Boolean active) {
        FnbPaymentLabelRequest request = new FnbPaymentLabelRequest();
        request.setName(name);
        request.setDisplayOrder(displayOrder);
        request.setActive(active);
        return request;
    }

    private PaymentLabelEntity existing(String name, int order, boolean active) {
        PaymentLabelEntity label = withId(PaymentLabelEntity.create(tenant, outlet, name, order), UUID.randomUUID());
        if (!active) label.update(name, order, false);
        return label;
    }

    @Test
    void createPersistsAnActiveLabelWithDefaultOrderCountPlusOneAndAudits() {
        when(guard.requireOwner(principal, outletId)).thenReturn(context);
        when(labels.count(tenantId, outletId)).thenReturn(3L);
        when(labels.existsName(tenantId, outletId, "GoPay", null)).thenReturn(false);

        FnbPaymentLabelResponse response = service.create(principal, outletId, request("GoPay", null, false));

        InOrder inOrder = inOrder(orders, labels);
        inOrder.verify(orders).lockTenant(tenantId);
        inOrder.verify(labels).persist(any(PaymentLabelEntity.class));
        assertEquals("GoPay", response.getName());
        assertTrue(response.isActive(), "a new label is always active");
        assertEquals(4, response.getDisplayOrder());
        ArgumentCaptor<AuditLogEntity> log = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(audit).persist(log.capture());
        assertEquals("PAYMENT_LABEL_CHANGED", log.getValue().getAction());
        assertEquals("PAYMENT_LABEL", log.getValue().getEntityType());
        assertEquals("name=GoPay active=true", log.getValue().getDetails());
    }

    @Test
    void createRefusesAtTwentyLabelsAndOnDuplicateNamesWithoutWriting() {
        when(guard.requireOwner(principal, outletId)).thenReturn(context);
        when(labels.count(tenantId, outletId)).thenReturn(20L, 5L);
        when(labels.existsName(tenantId, outletId, "QRIS", null)).thenReturn(true);

        OperationConflictException full = assertThrows(OperationConflictException.class,
                () -> service.create(principal, outletId, request("Baru", null, null)));
        assertEquals("Jumlah label maksimal 20.", full.getMessage());
        OperationConflictException duplicate = assertThrows(OperationConflictException.class,
                () -> service.create(principal, outletId, request("QRIS", null, null)));
        assertEquals("Label sudah ada.", duplicate.getMessage());
        verify(labels, never()).persist(any(PaymentLabelEntity.class));
        verify(audit, never()).persist(any(AuditLogEntity.class));
    }

    @Test
    void createRejectsAnInvalidNameFromTheEntity() {
        when(guard.requireOwner(principal, outletId)).thenReturn(context);
        when(labels.count(tenantId, outletId)).thenReturn(1L);
        assertThrows(InvalidOperationException.class,
                () -> service.create(principal, outletId, request("   ", null, null)));
        assertThrows(InvalidOperationException.class,
                () -> service.create(principal, outletId, request("x".repeat(41), null, null)));
        verify(labels, never()).persist(any(PaymentLabelEntity.class));
    }

    @Test
    void updateKeepsOmittedOrderAndActiveAndExcludesItselfFromTheDuplicateCheck() {
        when(guard.requireOwner(principal, outletId)).thenReturn(context);
        PaymentLabelEntity label = existing("Transfer", 2, true);
        UUID id = label.getId();
        when(labels.byId(tenantId, outletId, id)).thenReturn(Optional.of(label));
        when(labels.existsName(tenantId, outletId, "TRANSFER", id)).thenReturn(false);

        FnbPaymentLabelResponse response = service.update(principal, outletId, id, request("TRANSFER", null, null));

        assertEquals("TRANSFER", response.getName());
        assertEquals(2, response.getDisplayOrder());
        assertTrue(response.isActive());
        verify(orders).lockTenant(tenantId);
        verify(labels, never()).persist(any(PaymentLabelEntity.class));
        ArgumentCaptor<AuditLogEntity> log = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(audit).persist(log.capture());
        assertEquals(id, log.getValue().getEntityId());
        assertEquals("name=TRANSFER active=true", log.getValue().getDetails());
    }

    @Test
    void updateCanDeactivateAndRefusesDuplicatesAndUnknownLabels() {
        when(guard.requireOwner(principal, outletId)).thenReturn(context);
        PaymentLabelEntity label = existing("Debit", 3, true);
        UUID id = label.getId();
        when(labels.byId(tenantId, outletId, id)).thenReturn(Optional.of(label));
        UUID unknown = UUID.randomUUID();
        when(labels.byId(tenantId, outletId, unknown)).thenReturn(Optional.empty());
        when(labels.existsName(tenantId, outletId, "QRIS", id)).thenReturn(true);
        when(labels.existsName(tenantId, outletId, "Debit", id)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class,
                () -> service.update(principal, outletId, unknown, request("X", null, null)));
        assertThrows(OperationConflictException.class,
                () -> service.update(principal, outletId, id, request("QRIS", null, null)));
        assertTrue(label.isActive(), "a refused update changes nothing");

        FnbPaymentLabelResponse response = service.update(principal, outletId, id, request("Debit", 7, false));
        assertFalse(response.isActive());
        assertEquals(7, response.getDisplayOrder());
        ArgumentCaptor<AuditLogEntity> log = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(audit).persist(log.capture());
        assertEquals("name=Debit active=false", log.getValue().getDetails());
    }

    @Test
    void listUsesTheOutletGuardForActiveOnlyAndTheOwnerGuardOtherwise() {
        when(guard.requireOutlet(principal, outletId)).thenReturn(context);
        when(guard.requireOwner(principal, outletId)).thenReturn(context);
        when(labels.list(tenantId, outletId, true)).thenReturn(List.of(existing("QRIS", 1, true)));
        when(labels.list(tenantId, outletId, false))
                .thenReturn(List.of(existing("QRIS", 1, true), existing("Debit", 3, false)));

        assertEquals(1, service.list(principal, outletId, true).size());
        assertEquals(2, service.list(principal, outletId, false).size());
        verify(guard).requireOutlet(principal, outletId);
        verify(guard).requireOwner(principal, outletId);
    }

    @Test
    void createDefaultsPersistsQrisTransferAndDebitInOrder() {
        service.createDefaults(tenant, outlet);

        ArgumentCaptor<PaymentLabelEntity> captor = ArgumentCaptor.forClass(PaymentLabelEntity.class);
        verify(labels, times(3)).persist(captor.capture());
        List<PaymentLabelEntity> created = captor.getAllValues();
        assertEquals(List.of("QRIS", "Transfer", "Debit"), created.stream().map(PaymentLabelEntity::getName).toList());
        assertEquals(List.of(1, 2, 3), created.stream().map(PaymentLabelEntity::getDisplayOrder).toList());
        assertTrue(created.stream().allMatch(PaymentLabelEntity::isActive));
        assertTrue(created.stream().allMatch(label -> label.getOutlet() == outlet));
        verify(audit, never()).persist(any(AuditLogEntity.class));
    }

    private static <T> T withId(T entity, UUID id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
