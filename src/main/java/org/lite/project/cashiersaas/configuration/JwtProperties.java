package org.lite.project.cashiersaas.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.security.jwt")
public class JwtProperties {

    private String issuer;
    private String audience;
    private String secret;
    private Duration accessTokenTtl = Duration.ofMinutes(15);
}
