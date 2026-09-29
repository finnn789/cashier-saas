package org.lite.project.cashiersaas.component;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.configuration.JwtProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RefreshCookieService {
    public static final String NAME = "cashier_refresh";
    private static final String PATH = "/api/v1/auth";

    private final JwtProperties jwtProperties;
    @Value("${app.security.refresh-cookie-secure:true}")
    private boolean secure;

    public void set(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(token)
                .maxAge(jwtProperties.getRefreshTokenTtl()).build().toString());
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("").maxAge(0).build().toString());
    }

    private ResponseCookie.ResponseCookieBuilder cookie(String token) {
        return ResponseCookie.from(NAME, token).httpOnly(true).secure(secure)
                .sameSite("Strict").path(PATH);
    }
}
