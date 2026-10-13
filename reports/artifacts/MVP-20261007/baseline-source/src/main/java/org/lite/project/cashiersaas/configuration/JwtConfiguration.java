package org.lite.project.cashiersaas.configuration;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.lite.project.cashiersaas.component.AudienceValidator;
import org.lite.project.cashiersaas.component.TenantJwtAuthenticationConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfiguration {

    private static final int MIN_HMAC_SHA256_KEY_BYTES = 32;

    @Bean
    public SecretKey jwtSigningKey(JwtProperties jwtProperties) {
        String configuredSecret = jwtProperties.getSecret();
        if (configuredSecret == null || configuredSecret.isBlank()) {
            throw new IllegalStateException("CASHIER_JWT_SECRET_B64 must be configured.");
        }

        byte[] secretBytes;
        try {
            secretBytes = Base64.getDecoder().decode(configuredSecret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("CASHIER_JWT_SECRET_B64 must be Base64-encoded.", exception);
        }

        if (secretBytes.length < MIN_HMAC_SHA256_KEY_BYTES) {
            throw new IllegalStateException("CASHIER_JWT_SECRET_B64 must decode to at least 32 bytes.");
        }

        return new SecretKeySpec(secretBytes, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    @Primary
    public JwtDecoder jwtDecoder(SecretKey jwtSigningKey, JwtProperties jwtProperties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> requiredClaims = jwt -> {
            if (jwt.getExpiresAt() == null || jwt.getIssuedAt() == null || jwt.getId() == null
                    || jwt.getId().isBlank() || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                        "Required JWT claims are missing.", null));
            }
            return OAuth2TokenValidatorResult.success();
        };
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(jwtProperties.getIssuer()),
                new AudienceValidator(jwtProperties.getAudience()), requiredClaims);
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean
    public Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        return new TenantJwtAuthenticationConverter();
    }
}
