package org.lite.project.cashiersaas.service;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.entity.shared.identity.AuthSessionEntity;
import org.lite.project.cashiersaas.repository.AuthSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FnbRealtimeAccessService {
    private final MerchantAccessGuard guard;
    private final AuthSessionRepository sessions;

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public boolean isCurrent(TenantPrincipal principal, Instant accessExpiresAt) {
        Instant now = Instant.now();
        if (principal == null || principal.getSessionId() == null || principal.getMembershipId() == null
                || principal.getOutletId() == null || accessExpiresAt == null || !accessExpiresAt.isAfter(now)) return false;
        try {
            AuthSessionEntity session = sessions.findById(principal.getSessionId(), principal.getUserId()).orElse(null);
            if (session == null || !session.isUsableAt(now) || session.getTenant() == null || session.getOutlet() == null || session.getMembership() == null || session.getRole() == null) return false;
            if (!principal.getTenantId().equals(session.getTenant().getId()) || !principal.getOutletId().equals(session.getOutlet().getId()) || !principal.getMembershipId().equals(session.getMembership().getId()) || !principal.getRoles().contains(session.getRole())) return false;
            MerchantAccessContext context = guard.requireOutlet(principal, principal.getOutletId());
            return principal.getMembershipId().equals(context.getMembership().getId()) && session.getRole() == context.getMembership().getRole();
        } catch (RuntimeException exception) { return false; }
    }
}
