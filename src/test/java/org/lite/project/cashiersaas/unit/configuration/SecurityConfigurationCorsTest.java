package org.lite.project.cashiersaas.unit.configuration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.configuration.SecurityConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("unit")
class SecurityConfigurationCorsTest {
    private static List<String> allowedOrigins(String property) {
        SecurityConfiguration configuration = new SecurityConfiguration();
        ReflectionTestUtils.setField(configuration, "allowedOrigins", property);
        UrlBasedCorsConfigurationSource source = (UrlBasedCorsConfigurationSource) configuration.corsConfigurationSource();
        return source.getCorsConfigurations().get("/**").getAllowedOrigins();
    }

    @Test
    void validOriginListIsAcceptedAndTrimmed() {
        assertEquals(List.of("http://localhost:5173", "https://app.example.com", "http://127.0.0.1:8080"),
                allowedOrigins(" http://localhost:5173 , https://app.example.com,http://127.0.0.1:8080 "));
    }

    @Test
    void wildcardOriginIsRejected() {
        assertThrows(IllegalStateException.class, () -> allowedOrigins("*"));
        assertThrows(IllegalStateException.class, () -> allowedOrigins("http://localhost:5173,*"));
    }

    @Test
    void originWithPathQueryFragmentUserInfoOrBadPortIsRejected() {
        for (String invalid : List.of("https://app.example.com/path", "https://app.example.com?x=1",
                "https://app.example.com#frag", "https://user@app.example.com", "https://app.example.com:0",
                "https://app.example.com:70000", "ftp://app.example.com", "app.example.com")) {
            assertThrows(IllegalStateException.class, () -> allowedOrigins(invalid), invalid);
        }
    }

    @Test
    void emptyOrBlankListIsRejected() {
        assertThrows(IllegalStateException.class, () -> allowedOrigins(""));
        assertThrows(IllegalStateException.class, () -> allowedOrigins(" , "));
    }
}
