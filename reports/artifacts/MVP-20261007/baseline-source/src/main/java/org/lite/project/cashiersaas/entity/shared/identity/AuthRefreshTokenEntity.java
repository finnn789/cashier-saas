package org.lite.project.cashiersaas.entity.shared.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;

import java.time.Instant;

@Getter
@Entity
@Table(name = "auth_refresh_tokens")
@NoArgsConstructor
public class AuthRefreshTokenEntity extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private AuthSessionEntity session;

    @Column(name = "token_digest", nullable = false, length = 64, unique = true)
    private String tokenDigest;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    public static AuthRefreshTokenEntity issue(AuthSessionEntity session, String digest, Instant expiresAt) {
        AuthRefreshTokenEntity token = new AuthRefreshTokenEntity();
        token.session = session;
        token.tokenDigest = digest;
        token.expiresAt = expiresAt;
        return token;
    }

    public boolean isUnusedAt(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    public void consume(Instant now) {
        usedAt = now;
    }
}
