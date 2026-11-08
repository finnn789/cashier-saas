package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lite.project.cashiersaas.component.MemberSyncRequestedEvent;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.Role;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes a {@link MemberSyncRequestedEvent} from inside the owning transaction. The after-commit listener
 * performs the HTTP push only when that transaction commits.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberSyncPublisher {
    private final ApplicationEventPublisher events;

    public void memberChanged(TenantEntity tenant, StaffUserEntity user, Role role) {
        String phoneNumber = user.getPhoneNumber();
        if (phoneNumber == null || phoneNumber.isBlank()) {
            log.warn("Member sync skipped because the user has no phone number: tenantId={} userId={}",
                    tenant.getId(), user.getId());
            return;
        }
        events.publishEvent(new MemberSyncRequestedEvent(tenant.getId(), tenant.getName(), user.getId(),
                user.getFullName(), phoneNumber, role));
    }
}
