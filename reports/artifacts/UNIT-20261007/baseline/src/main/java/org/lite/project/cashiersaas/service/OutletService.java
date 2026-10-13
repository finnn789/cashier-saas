package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.CashierMapper;
import org.lite.project.cashiersaas.dto.response.OutletResponse;
import org.lite.project.cashiersaas.repository.OutletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OutletService {
    private final OutletRepository outletRepository;
    private final MerchantAccessGuard merchantAccessGuard;

    @Transactional(readOnly = true)
    public List<OutletResponse> listAccessible(TenantPrincipal principal) {
        merchantAccessGuard.requireOutlet(principal, principal.getOutletId());
        return outletRepository.findActive(principal.getTenantId(), principal.getOutletId())
                .map(outlet -> List.of(CashierMapper.toOutletResponse(outlet)))
                .orElseGet(List::of);
    }
}
