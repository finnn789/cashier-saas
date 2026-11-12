package org.lite.project.cashiersaas.configuration;

import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.component.ApiSecurityExceptionHandler;
import org.lite.project.cashiersaas.component.HttpRateLimiter;
import org.lite.project.cashiersaas.component.RateLimitFilter;
import org.lite.project.cashiersaas.shared.RateLimitConstants;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {
    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter,
            ApiSecurityExceptionHandler securityExceptionHandler,
            HttpRateLimiter httpRateLimiter,
            ObjectMapper objectMapper) {
        return http
                .cors(cors-> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .requireCsrfProtectionMatcher(request -> request.getMethod().equals("POST")
                        && (request.getServletPath().equals("/api/v1/auth/refresh")
                        || request.getServletPath().equals("/api/v1/auth/logout"))))
                .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception ->
                        exception.authenticationEntryPoint(securityExceptionHandler)
                                .accessDeniedHandler(securityExceptionHandler)).authorizeHttpRequests(authorize -> authorize
                                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/register").permitAll()
                                .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                                .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                                .requestMatchers("/api/v1/auth/select-merchant", "/api/v1/auth/outlets").hasAuthority("SCOPE_ACCOUNT")
                                .requestMatchers("/api/v1/onboarding/**").hasAuthority("SCOPE_ACCOUNT")
                                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                                .requestMatchers(HttpMethod.GET, "/api/v1/public/plans").permitAll()
                                .requestMatchers("/api/v1/platform/**").hasRole(Role.SUPERADMIN.name())
                                .requestMatchers("/api/v1/merchant/**").hasRole(Role.OWNERMERCHANT.name())
                                .requestMatchers("/api/v1/cashier/**").hasAnyRole(Role.OWNERMERCHANT.name(),
                                        Role.CASHIER.name()).anyRequest().authenticated()).oauth2ResourceServer(
                                                resourceServer -> resourceServer
                                                        .authenticationEntryPoint(securityExceptionHandler)
                                                        .accessDeniedHandler(securityExceptionHandler)
                                                        .jwt(jwt ->
                                                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                // Not a bean on purpose: it must run after the Bearer filter so the user is known.
                .addFilterAfter(new RateLimitFilter(httpRateLimiter, objectMapper),
                        BearerTokenAuthenticationFilter.class)
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins(allowedOrigins));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("X-XSRF-TOKEN", RateLimitConstants.RETRY_AFTER_HEADER));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static List<String> allowedOrigins(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalStateException("app.cors.allowed-origins must not be empty.");
        List<String> origins = Arrays.stream(raw.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList();
        if (origins.isEmpty()) throw new IllegalStateException("app.cors.allowed-origins must not be empty.");
        for (String origin : origins) validateOrigin(origin);
        return origins;
    }

    private static void validateOrigin(String origin) {
        URI uri = URI.create(origin);
        if (origin.contains("*") || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getPort() == 0 || uri.getPort() > 65_535 || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty())) {
            throw new IllegalStateException("Invalid explicit origin in app.cors.allowed-origins.");
        }
    }
}
