package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.CashierMapper;
import org.lite.project.cashiersaas.dto.response.OutletResponse;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.repository.MembershipOutletAccessRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OutletService {
    private final MerchantAccessGuard accessGuard;
    private final MembershipOutletAccessRepository accessRepository;

    @Transactional(readOnly = true)
    public List<OutletResponse> listAccessible(TenantPrincipal principal) {
        TenantMembershipEntity membership = accessGuard.requireMember(principal);
        return accessRepository.listActiveForMembership(principal.getTenantId(), membership.getId()).stream()
                .map(access -> CashierMapper.toOutletResponse(access.getOutlet()))
                .toList();
    }
}
