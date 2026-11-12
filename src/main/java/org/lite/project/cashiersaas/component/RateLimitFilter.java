package org.lite.project.cashiersaas.component;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lite.project.cashiersaas.component.HttpRateLimiter.Decision;
import org.lite.project.cashiersaas.component.HttpRateLimiter.Group;
import org.lite.project.cashiersaas.shared.RateLimitConstants;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Set;

/**
 * Throttles the REST API with {@link HttpRateLimiter}. Deliberately NOT a Spring bean: it is created in
 * {@code SecurityConfiguration} and placed after the Bearer filter, so the authenticated user is known.
 * The client IP is {@code getRemoteAddr()} only; X-Forwarded-For is never read here (spoofable).
 */
@Slf4j
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> AUTH_PATHS = Set.of(
            "/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/refresh");

    private final HttpRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !rateLimiter.isEnabled()
                || HttpMethod.OPTIONS.matches(request.getMethod())
                || !path(request).startsWith(RateLimitConstants.API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = path(request);
        boolean post = HttpMethod.POST.matches(request.getMethod());
        String ip = request.getRemoteAddr();
        Authentication authentication = authenticatedUser();

        Decision decision;
        Group group;
        if (post && AUTH_PATHS.contains(path)) {
            group = Group.AUTH;
            decision = rateLimiter.check(group, ip);
        } else if (authentication != null) {
            group = Group.AUTHENTICATED;
            decision = rateLimiter.check(group, userKey(authentication));
        } else {
            group = Group.ANONYMOUS_OTHER;
            decision = rateLimiter.check(group, ip);
        }

        if (decision.isAllowed()) {
            filterChain.doFilter(request, response);
            return;
        }
        log.warn("Rate limit exceeded: group={}", group);
        reject(response, decision.getRetryAfterSeconds());
    }

    private static String path(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        return request.getServletPath() + (pathInfo == null ? "" : pathInfo);
    }

    private static Authentication authenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication;
    }

    /** Stable user id: the JWT principals do not override toString(), so getName() would not be stable. */
    private static String userKey(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof TenantPrincipal tenantPrincipal) {
            return tenantPrincipal.getUserId().toString();
        }
        if (principal instanceof AccountPrincipal accountPrincipal) {
            return accountPrincipal.getUserId().toString();
        }
        return authentication.getName();
    }

    private void reject(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(RateLimitConstants.RETRY_AFTER_HEADER,Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ApiResponse<Object> payload = ApiResponse.error(HttpStatus.TOO_MANY_REQUESTS.value(),
                "Terlalu banyak permintaan. Coba lagi dalam " + retryAfterSeconds + " detik.");
        response.getOutputStream().write(objectMapper.writeValueAsBytes(payload));
    }
}
