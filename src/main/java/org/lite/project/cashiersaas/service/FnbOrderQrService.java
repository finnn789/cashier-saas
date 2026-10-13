package org.lite.project.cashiersaas.service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.FnbMenuMapper;
import org.lite.project.cashiersaas.dto.response.FnbQrResponse;
import org.lite.project.cashiersaas.entity.fnb.TableQrTokenEntity;
import org.lite.project.cashiersaas.repository.FnbTableRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
@Service @RequiredArgsConstructor
public class FnbOrderQrService {
    private final MerchantAccessGuard guard;
    private final FnbTableRepository tables;
    private final FnbOrderRepository orders;
    @Transactional(readOnly = true)
    public FnbQrResponse get(TenantPrincipal principal, UUID outletId) {
        guard.requireOwner(principal, outletId);
        return FnbMenuMapper.qr(tables.outletQr(principal.getTenantId(), outletId)
                .orElseThrow(org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException::new));
    }
    @Transactional
    public FnbQrResponse outletQr(TenantPrincipal p, UUID outlet, boolean rotate) {
        orders.lockTenant(p.getTenantId());
        var context = guard.requireOwner(p, outlet);
        var qr = tables.outletQr(p.getTenantId(), outlet).orElseGet(() -> {
            if (rotate) throw new org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException();
            var created = TableQrTokenEntity.outlet(context.getTenant(), context.getOutlet(), token());
            tables.persist(created); return created;
        });
        if (rotate) qr.rotate(token());
        return FnbMenuMapper.qr(qr);
    }
    private String token() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
