package org.lite.project.cashiersaas.component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.exception.ErrorMessage;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class ApiSecurityExceptionHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final ObjectMapper objectMapper;
    private final BearerTokenAuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final BearerTokenAccessDeniedHandler bearerAccessDeniedHandler = new BearerTokenAccessDeniedHandler();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        // Preserve the Bearer challenge and protocol status before writing the API envelope.
        bearerEntryPoint.commence(request, response, exception);
        boolean hasAuthorization = request.getHeader("Authorization") != null;
        ErrorMessage message = hasAuthorization ? ErrorMessage.AUTHENTICATION_INVALID
                : ErrorMessage.AUTHENTICATION_REQUIRED;
        if (response.getStatus() == HttpServletResponse.SC_BAD_REQUEST) {
            message = ErrorMessage.AUTHORIZATION_HEADER_INVALID;
        }
        write(response, ApiResponse.error(response.getStatus(), message.getMessage()));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        bearerAccessDeniedHandler.handle(request, response, exception);
        ErrorMessage message = exception instanceof CsrfException ? ErrorMessage.CSRF_INVALID
                : ErrorMessage.ACCESS_FORBIDDEN;
        write(response, ApiResponse.error(response.getStatus(), message.getMessage()));
    }

    private void write(HttpServletResponse response, ApiResponse<Object> payload) throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getOutputStream().write(objectMapper.writeValueAsBytes(payload));
    }
}
