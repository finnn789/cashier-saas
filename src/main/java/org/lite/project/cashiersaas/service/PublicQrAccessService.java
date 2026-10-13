package org.lite.project.cashiersaas.service;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.fnb.TableQrTokenEntity;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.FnbTableRepository;
import org.lite.project.cashiersaas.repository.SubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PublicQrAccessService {
    private final FnbTableRepository tables;
    private final SubscriptionRepository subscriptions;

    @Transactional(readOnly = true)
    public TableQrTokenEntity requirePublicQr(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw new ResourceNotFoundException();
        TableQrTokenEntity qr = tables.byToken(token).orElseThrow(ResourceNotFoundException::new);
        if ((qr.getDiningTable() != null && !qr.getDiningTable().isActive()) || !qr.getOutlet().isActive() || !qr.getTenant().isActive()
                || !qr.getTenant().isFnbEnabled()
                || subscriptions.findActiveForTenant(qr.getTenant().getId())
                    .filter(s -> "FREETIER".equals(s.getPlanCode()) && s.isActiveAt(Instant.now())).isEmpty()) {
            throw new ResourceNotFoundException();
        }
        return qr;
    }
}
