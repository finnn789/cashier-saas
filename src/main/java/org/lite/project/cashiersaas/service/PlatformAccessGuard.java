package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.PlatformPrincipal;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.exception.ErrorMessage;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.StaffUserRepository;
import org.springframework.stereotype.Component;

/** Re-reads the admin on every platform request so revoking the flag or disabling the account is immediate. */
@Component
@RequiredArgsConstructor
public class PlatformAccessGuard {
    private final StaffUserRepository staffUsers;

    /** The admin account; 404 (not 403) for anything else so the panel does not reveal itself. */
    public StaffUserEntity require(PlatformPrincipal principal) {
        if (principal == null) {
            throw new ResourceNotFoundException(ErrorMessage.RESOURCE_UNAVAILABLE.getMessage());
        }
        return staffUsers.findById(principal.getUserId())
                .filter(user -> user.isActive() && user.isPlatformAdmin())
                .orElseThrow(() -> new ResourceNotFoundException(ErrorMessage.RESOURCE_UNAVAILABLE.getMessage()));
    }
}
