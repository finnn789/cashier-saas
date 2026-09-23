package org.lite.project.cashiersaas.configuration;

import org.lite.project.cashiersaas.enums.Role;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter) {
        return http.csrf(AbstractHttpConfigurer::disable).sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception ->
                        exception.authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint())
                                .accessDeniedHandler(new BearerTokenAccessDeniedHandler())).authorizeHttpRequests(authorize -> authorize
                                .requestMatchers(HttpMethod.POST, "/api/v1/auth/**").permitAll()
                                .requestMatchers("/api/v1/onboarding/**").hasAuthority("SCOPE_ACCOUNT")
                                .requestMatchers("/api/v1/public/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                                .requestMatchers("/api/v1/platform/**").hasRole(Role.SUPERADMIN.name())
                                .requestMatchers("/api/v1/merchant/**").hasAnyRole(Role.SUPERADMIN.name(), Role.OWNERMERCHANT.name())
                                .requestMatchers("/api/v1/cashier/**").hasAnyRole(Role.SUPERADMIN.name(), Role.OWNERMERCHANT.name(),
                                        Role.CASHIER.name()).anyRequest().authenticated()).oauth2ResourceServer(
                                                resourceServer -> resourceServer.jwt(jwt ->
                                                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))).build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
