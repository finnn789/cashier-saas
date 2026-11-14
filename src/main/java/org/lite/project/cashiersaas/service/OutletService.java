package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.CashierMapper;
import org.lite.project.cashiersaas.dto.request.MerchantOutletRequest;
import org.lite.project.cashiersaas.dto.response.MerchantOutletListResponse;
import org.lite.project.cashiersaas.dto.response.MerchantOutletResponse;
import org.lite.project.cashiersaas.dto.response.OutletResponse;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.entity.shared.identity.MembershipOutletAccessEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.repository.MembershipOutletAccessRepository;
import org.lite.project.cashiersaas.repository.OutletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutletService {
    private final OutletRepository outletRepository;
    private final MerchantAccessGuard merchantAccessGuard;
    private final PlanEntitlementService planEntitlementService;
    private final FnbOrderRepository orders;
    private final AuditLogRepository auditLogs;
    private final MembershipOutletAccessRepository accessRepository;
    private final FnbPaymentLabelService paymentLabelService;

    @Transactional(readOnly = true)
    public List<OutletResponse> listAccessible(TenantPrincipal principal) {
        merchantAccessGuard.requireOutlet(principal, principal.getOutletId());
        return outletRepository.findActive(principal.getTenantId(), principal.getOutletId())
                .map(outlet -> List.of(CashierMapper.toOutletResponse(outlet)))
                .orElseGet(List::of);
    }

    @Transactional(readOnly = true)
    public MerchantOutletListResponse listAll(TenantPrincipal principal) {
        requireOwner(principal);
        Instant now = Instant.now();
        List<OutletEntity> outlets = outletRepository.listByTenant(principal.getTenantId());
        Set<UUID> usable = Set.copyOf(planEntitlementService.usableOutletIds(principal.getTenantId(), now));
        MerchantOutletListResponse response = new MerchantOutletListResponse();
        response.setOutlets(outlets.stream()
                .map(outlet -> CashierMapper.toMerchantOutletResponse(outlet, usable.contains(outlet.getId()))).toList());
        response.setAllowedOutlets(planEntitlementService.allowedOutletCount(principal.getTenantId(), now));
        response.setActiveOutlets(countActive(outlets));
        response.setExtraOutletPrice(MembershipPlanCatalogService.OUTLET_ADDON_MONTHLY);
        return response;
    }

    @Transactional
    public MerchantOutletResponse create(TenantPrincipal principal, MerchantOutletRequest request) {
        orders.lockTenant(principal.getTenantId());
        MerchantAccessContext context = requireOwner(principal);
        Values values = normalize(request);
        if (outletRepository.nameTaken(principal.getTenantId(), values.name(), null)) {
            throw new OperationConflictException("Nama outlet sudah dipakai.");
        }
        Instant now = Instant.now();
        planEntitlementService.requireOutletCapacity(principal.getTenantId(),
                countActive(outletRepository.listByTenant(principal.getTenantId())), now);
        OutletEntity outlet = OutletEntity.open(context.getTenant(), values.name(), values.timeZone(), values.address(),
                values.phoneNumber());
        outletRepository.persist(outlet);
        paymentLabelService.createDefaults(context.getTenant(), outlet);
        grantOwners(principal.getTenantId(), outlet);
        record(context, outlet, AuditLogEntity.Action.OUTLET_CREATED, details(outlet));
        return response(outlet, now);
    }

    @Transactional
    public MerchantOutletResponse update(TenantPrincipal principal, UUID outletId, MerchantOutletRequest request) {
        orders.lockTenant(principal.getTenantId());
        MerchantAccessContext context = requireOwner(principal);
        OutletEntity outlet = findInTenant(principal, outletId);
        Values values = normalize(request);
        if (outletRepository.nameTaken(principal.getTenantId(), values.name(), outlet.getId())) {
            throw new OperationConflictException("Nama outlet sudah dipakai.");
        }
        String before = details(outlet);
        if (outlet.updateBusinessSettings(values.name(), values.timeZone(), values.address(), values.phoneNumber())) {
            record(context, outlet, AuditLogEntity.Action.OUTLET_UPDATED, "before: " + before + " | after: " + details(outlet));
        }
        return response(outlet, Instant.now());
    }

    /** Always allowed, also while over the allowance, except for the last active outlet or one with an open shift. */
    @Transactional
    public MerchantOutletResponse deactivate(TenantPrincipal principal, UUID outletId) {
        orders.lockTenant(principal.getTenantId());
        orders.lockOutlet(principal.getTenantId(), outletId);
        MerchantAccessContext context = requireOwner(principal);
        OutletEntity outlet = findInTenant(principal, outletId);
        if (outlet.isActive()) {
            if (countActive(outletRepository.listByTenant(principal.getTenantId())) <= 1) {
                throw new OperationConflictException("Outlet aktif terakhir tidak dapat dinonaktifkan.");
            }
            if (outletRepository.hasOpenShift(principal.getTenantId(), outlet.getId())) {
                throw new OperationConflictException("Outlet masih memiliki shift terbuka. Tutup shift terlebih dahulu.");
            }
            outlet.deactivate();
            record(context, outlet, AuditLogEntity.Action.OUTLET_DEACTIVATED, details(outlet));
        }
        return response(outlet, Instant.now());
    }

    @Transactional
    public MerchantOutletResponse reactivate(TenantPrincipal principal, UUID outletId) {
        orders.lockTenant(principal.getTenantId());
        MerchantAccessContext context = requireOwner(principal);
        OutletEntity outlet = findInTenant(principal, outletId);
        Instant now = Instant.now();
        if (!outlet.isActive()) {
            planEntitlementService.requireOutletCapacity(principal.getTenantId(),
                    countActive(outletRepository.listByTenant(principal.getTenantId())), now);
            outlet.reactivate();
            grantOwners(principal.getTenantId(), outlet);
            record(context, outlet, AuditLogEntity.Action.OUTLET_REACTIVATED, details(outlet));
        }
        return response(outlet, now);
    }

    private MerchantAccessContext requireOwner(TenantPrincipal principal) {
        return merchantAccessGuard.requireOwner(principal, principal.getOutletId());
    }

    private OutletEntity findInTenant(TenantPrincipal principal, UUID outletId) {
        return outletRepository.findInTenant(principal.getTenantId(), outletId).orElseThrow(ResourceNotFoundException::new);
    }

    private static int countActive(List<OutletEntity> outlets) {
        return (int) outlets.stream().filter(OutletEntity::isActive).count();
    }

    /** Owners reach every outlet of the tenant; cashiers are granted per outlet by the staff flow. */
    private void grantOwners(UUID tenantId, OutletEntity outlet) {
        for (TenantMembershipEntity owner : outletRepository.activeOwnerMemberships(tenantId)) {
            if (accessRepository.findAccess(tenantId, owner.getId(), outlet.getId()).isEmpty()) {
                accessRepository.persist(MembershipOutletAccessEntity.grant(owner, outlet));
            }
        }
    }

    private MerchantOutletResponse response(OutletEntity outlet, Instant now) {
        boolean usable = outlet.isActive()
                && planEntitlementService.usableOutletIds(outlet.getTenant().getId(), now).contains(outlet.getId());
        return CashierMapper.toMerchantOutletResponse(outlet, usable);
    }

    private void record(MerchantAccessContext context, OutletEntity outlet, AuditLogEntity.Action action, String details) {
        auditLogs.persist(AuditLogEntity.recordAction(context.getTenant(), outlet, context.getMembership().getUser(),
                action, outlet.getId(), null, details));
    }

    private static String details(OutletEntity outlet) {
        return "name=" + outlet.getName() + " timeZone=" + outlet.getTimeZone() + " address=" + outlet.getAddress()
                + " phoneNumber=" + outlet.getPhoneNumber() + " active=" + outlet.isActive();
    }

    private static Values normalize(MerchantOutletRequest request) {
        String name = request.getName().trim();
        if (name.isEmpty() || name.length() > 120) {
            throw new InvalidOperationException("Nama outlet wajib diisi dan tidak boleh melebihi 120 karakter.");
        }
        try {
            return new Values(name, ZoneId.of(request.getTimeZone().trim()).getId(), optional(request.getAddress()),
                    optional(request.getPhoneNumber()));
        } catch (RuntimeException exception) {
            throw new InvalidOperationException("Zona waktu outlet tidak valid.");
        }
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Values(String name, String timeZone, String address, String phoneNumber) { }
}
