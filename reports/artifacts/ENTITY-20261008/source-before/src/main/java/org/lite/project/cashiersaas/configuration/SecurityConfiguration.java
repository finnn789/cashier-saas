package org.lite.project.cashiersaas.configuration;

import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.component.ApiSecurityExceptionHandler;
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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {
    @Value("${app.fnb.realtime.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
    private String realtimeAllowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter,
            ApiSecurityExceptionHandler securityExceptionHandler) {
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
                                .requestMatchers("/api/v1/public/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                                // The native upgrade cannot carry a Bearer header. The handshake interceptor consumes a one-time ticket.
                                .requestMatchers(HttpMethod.GET, "/api/v1/fnb/realtime").permitAll()
                                .requestMatchers("/api/v1/platform/**").hasRole(Role.SUPERADMIN.name())
                                .requestMatchers("/api/v1/merchant/**").hasRole(Role.OWNERMERCHANT.name())
                                .requestMatchers("/api/v1/cashier/**").hasAnyRole(Role.OWNERMERCHANT.name(),
                                        Role.CASHIER.name()).anyRequest().authenticated()).oauth2ResourceServer(
                                                resourceServer -> resourceServer
                                                        .authenticationEntryPoint(securityExceptionHandler)
                                                        .accessDeniedHandler(securityExceptionHandler)
                                                        .jwt(jwt ->
                                                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))).build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(FnbWebSocketConfiguration.allowedOrigins(realtimeAllowedOrigins));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("X-XSRF-TOKEN"));
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
}
