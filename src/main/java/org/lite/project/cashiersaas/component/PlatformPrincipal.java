package org.lite.project.cashiersaas.component;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

/** Caller of a PLATFORM token: a SUPERADMIN account with no tenant. */
@Getter
@RequiredArgsConstructor
public class PlatformPrincipal {

    private final UUID userId;
    private final String email;
    private final UUID sessionId;

    /** Stable per user: the rate limiter falls back to the principal's string form as its key. */
    @Override
    public String toString() {
        return userId.toString();
    }
}
