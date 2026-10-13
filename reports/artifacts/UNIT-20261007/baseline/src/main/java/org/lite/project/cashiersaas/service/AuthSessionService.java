package org.lite.project.cashiersaas.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.AccountPrincipal;
import org.lite.project.cashiersaas.component.JwtTokenService;
import org.lite.project.cashiersaas.component.MerchantTokenContext;
import org.lite.project.cashiersaas.configuration.JwtProperties;
import org.lite.project.cashiersaas.entity.shared.billing.SubscriptionEntity;
import org.lite.project.cashiersaas.entity.shared.identity.AuthRefreshTokenEntity;
import org.lite.project.cashiersaas.entity.shared.identity.AuthSessionEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.exception.ErrorMessage;
import org.lite.project.cashiersaas.exception.runtime.SessionInvalidException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.repository.AuthRefreshTokenRepository;
import org.lite.project.cashiersaas.repository.AuthSessionRepository;
import org.lite.project.cashiersaas.repository.MembershipOutletAccessRepository;
import org.lite.project.cashiersaas.repository.OutletRepository;
import org.lite.project.cashiersaas.repository.SubscriptionRepository;
import org.lite.project.cashiersaas.repository.TenantMembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthSessionService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthSessionRepository sessionRepository;
    private final AuthRefreshTokenRepository tokenRepository;
    private final TenantMembershipRepository membershipRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final OutletRepository outletRepository;
    private final MembershipOutletAccessRepository accessRepository;
    private final JwtTokenService jwtTokenService;
    private final JwtProperties jwtProperties;

    @Transactional
    public IssuedTokens start(StaffUserEntity user) {
        Instant now = Instant.now();
        AuthSessionEntity session = AuthSessionEntity.start(user, now.plus(jwtProperties.getRefreshTokenTtl()));
        sessionRepository.persist(session);
        String rawRefresh = newRefreshToken();
        tokenRepository.persist(AuthRefreshTokenEntity.issue(session, digest(rawRefresh), session.getExpiresAt()));
        Instant accessExpiry = min(now.plus(jwtProperties.getAccessTokenTtl()), session.getExpiresAt());
        String access = jwtTokenService.generateAccountAccessToken(user.getId(), user.getEmail(),
                session.getId(), accessExpiry);
        return new IssuedTokens(access, accessExpiry, rawRefresh,
                user.isPendingProfile() ? "COMPLETE_ONBOARDING" : "SELECT_MERCHANT_OUTLET", null, null, null);
    }


    @Transactional
    public IssuedTokens selectMerchant(AccountPrincipal principal, TenantMembershipEntity membership,
                                       OutletEntity outlet, SubscriptionEntity subscription) {
        AuthSessionEntity session = sessionRepository.findById(principal.getSessionId(), principal.getUserId())
                .orElseThrow(SessionInvalidException::new);
        sessionRepository.lockAndRefresh(session);
        if (!session.isUsableAt(Instant.now()) || !membership.getUser().getId().equals(principal.getUserId())) {
            throw new SessionInvalidException();
        }
        if (!membership.getTenant().isFnbEnabled()) {
            throw new SessionInvalidException();
        }
        session.selectMerchant(membership, outlet);
        Instant expiresAt = merchantAccessExpiry(session, subscription);
        String access = jwtTokenService.generateAccessToken(MerchantTokenContext.builder()
                .userId(principal.getUserId())
                .tenantId(membership.getTenant().getId())
                .outletId(outlet.getId())
                .membershipId(membership.getId())
                .sessionId(session.getId())
                .email(membership.getUser().getEmail())
                .roles(List.of(membership.getRole()))
                .expiresAt(expiresAt)
                .build());
        return new IssuedTokens(access, expiresAt, null, "OPERATE", membership.getTenant().getId(),
                outlet.getId(), membership.getRole());
    }

    @Transactional(noRollbackFor = SessionInvalidException.class)
    public IssuedTokens refresh(String rawRefresh) {
        AuthRefreshTokenEntity token = tokenRepository.findByDigest(digestValidated(rawRefresh))
                .orElseThrow(SessionInvalidException::new);
        AuthSessionEntity session = token.getSession();
        sessionRepository.lockAndRefresh(session);
        tokenRepository.refresh(token);
        Instant now = Instant.now();
        if (!session.isUsableAt(now) || !token.isUnusedAt(now)) {
            session.revoke(now);
            throw new SessionInvalidException();
        }
        StaffUserEntity user = session.getUser();
        IssuedTokens access = issueForExistingSession(session, user, now);
        token.consume(now);
        String newRawRefresh = newRefreshToken();
        tokenRepository.persist(AuthRefreshTokenEntity.issue(session, digest(newRawRefresh), session.getExpiresAt()));
        return new IssuedTokens(access.getAccessToken(), access.getExpiresAt(), newRawRefresh,
                access.getNextStep(), access.getTenantId(), access.getOutletId(), access.getRole());
    }

    @Transactional
    public void logout(String rawRefresh) {
        if (rawRefresh == null || rawRefresh.length() != 43) {
            return;
        }
        tokenRepository.findByDigest(digest(rawRefresh)).ifPresent(token -> {
            AuthSessionEntity session = token.getSession();
            sessionRepository.lockAndRefresh(session);
            session.revoke(Instant.now());
        });
    }

    private IssuedTokens issueForExistingSession(AuthSessionEntity session, StaffUserEntity user, Instant now) {
        if (session.getTenant() == null) {
            if (!user.isActive() && !user.isPendingProfile()) {
                session.revoke(now);
                throw new SessionInvalidException();
            }
            Instant expiry = min(now.plus(jwtProperties.getAccessTokenTtl()), session.getExpiresAt());
            String access = jwtTokenService.generateAccountAccessToken(user.getId(), user.getEmail(),
                    session.getId(), expiry);
            return new IssuedTokens(access, expiry, null,
                    user.isPendingProfile() ? "COMPLETE_ONBOARDING" : "SELECT_MERCHANT_OUTLET", null, null, null);
        }
        if (!user.isActive() || !session.getTenant().isActive() || !session.getTenant().isFnbEnabled()) {
            session.revoke(now);
            throw new SessionInvalidException();
        }
        UUID tenantId = session.getTenant().getId();
        UUID outletId = session.getOutlet().getId();
        TenantMembershipEntity membership = membershipRepository.findActive(user.getId(), tenantId)
                .filter(value -> value.getId().equals(session.getMembership().getId()))
                .filter(value -> value.getRole() == session.getRole())
                .orElse(null);
        SubscriptionEntity subscription = subscriptionRepository.findActiveForTenant(tenantId).orElse(null);
        if (membership == null || subscription == null || !subscription.isActiveAt(now)
                || outletRepository.findActive(tenantId, outletId).isEmpty()
                || accessRepository.findAccess(tenantId, membership.getId(), outletId).isEmpty()) {
            session.revoke(now);
            throw new SessionInvalidException();
        }
        Instant expiry = min(now.plus(jwtProperties.getAccessTokenTtl()), session.getExpiresAt());
        if (subscription.getEndsAt() != null) {
            expiry = min(expiry, subscription.getEndsAt());
        }
        String access = jwtTokenService.generateAccessToken(MerchantTokenContext.builder()
                .userId(user.getId())
                .tenantId(tenantId)
                .outletId(outletId)
                .membershipId(membership.getId())
                .sessionId(session.getId())
                .email(user.getEmail())
                .roles(List.of(membership.getRole()))
                .expiresAt(expiry)
                .build());
        return new IssuedTokens(access, expiry, null, "OPERATE", tenantId, outletId, membership.getRole());
    }

    private Instant merchantAccessExpiry(AuthSessionEntity session, SubscriptionEntity subscription) {
        Instant now = Instant.now();
        if (subscription == null || !subscription.isActiveAt(now)
                || !subscription.getTenant().getId().equals(session.getTenant().getId())) {
            throw new OperationConflictException(ErrorMessage.SUBSCRIPTION_INACTIVE.getMessage());
        }
        Instant expiry = min(now.plus(jwtProperties.getAccessTokenTtl()), session.getExpiresAt());
        return subscription.getEndsAt() == null ? expiry : min(expiry, subscription.getEndsAt());
    }

    private Instant min(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private String newRefreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String digestValidated(String value) {
        if (value == null || value.length() != 43) {
            throw new SessionInvalidException();
        }
        return digest(value);
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    @Getter
    @RequiredArgsConstructor
    public static class IssuedTokens {
        private final String accessToken;
        private final Instant expiresAt;
        private final String refreshToken;
        private final String nextStep;
        private final UUID tenantId;
        private final UUID outletId;
        private final Role role;
    }
}
