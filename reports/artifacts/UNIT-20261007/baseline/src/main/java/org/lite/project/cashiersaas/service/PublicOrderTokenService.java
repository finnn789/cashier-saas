package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PublicOrderTokenService {
    private final SecretKey jwtSigningKey;

    // Domain separation prevents interchange with JWTs or any other HMAC use.
    public String token(UUID tenantId, String idempotencyKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(jwtSigningKey);
            byte[] bytes = mac.doFinal(("fnb-public-order:v1:" + tenantId + ":" + idempotencyKey)
                    .getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot issue public order token", exception);
        }
    }
    public String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
