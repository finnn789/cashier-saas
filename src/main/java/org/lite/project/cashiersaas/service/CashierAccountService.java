package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.CreateCashierRequest;
import org.lite.project.cashiersaas.dto.response.CashierAccountResponse;
import org.lite.project.cashiersaas.entity.shared.identity.MembershipOutletAccessEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.exception.runtime.EmailAlreadyRegisteredException;
import org.lite.project.cashiersaas.repository.MembershipOutletAccessRepository;
import org.lite.project.cashiersaas.repository.StaffUserRepository;
import org.lite.project.cashiersaas.repository.TenantMembershipRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CashierAccountService {
    private final MerchantAccessGuard accessGuard;
    private final StaffUserRepository staffUserRepository;
    private final TenantMembershipRepository membershipRepository;
    private final MembershipOutletAccessRepository outletAccessRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public CashierAccountResponse create(TenantPrincipal principal, UUID outletId, CreateCashierRequest request) {
        MerchantAccessContext context = accessGuard.requireOwner(principal, outletId);
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        if (staffUserRepository.findByNormalizedEmail(email).isPresent()) {
            throw new EmailAlreadyRegisteredException();
        }
        StaffUserEntity user = StaffUserEntity.createCashier(email,
                passwordEncoder.encode(request.getPassword()), request.getFullName().trim());
        staffUserRepository.persistAndFlush(user);
        TenantMembershipEntity membership = TenantMembershipEntity.grantCashier(user, context.getTenant());
        membershipRepository.persist(membership);
        outletAccessRepository.persist(MembershipOutletAccessEntity.grant(membership, context.getOutlet()));

        CashierAccountResponse response = new CashierAccountResponse();
        response.setUserId(user.getId());
        response.setOutletId(outletId);
        response.setEmail(user.getEmail());
        response.setFullName(user.getFullName());
        response.setRole(membership.getRole().name());
        return response;
    }
}
