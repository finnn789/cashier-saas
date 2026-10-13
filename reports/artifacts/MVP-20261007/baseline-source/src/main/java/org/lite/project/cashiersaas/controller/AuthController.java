package org.lite.project.cashiersaas.controller;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.dto.request.identity.LoginRequest;
import org.lite.project.cashiersaas.dto.request.identity.RegisterUserRequest;
import org.lite.project.cashiersaas.dto.request.identity.SelectMerchantRequest;
import org.lite.project.cashiersaas.component.AccountPrincipal;
import org.lite.project.cashiersaas.component.RefreshCookieService;
import org.lite.project.cashiersaas.dto.response.identity.LoginResponse;
import org.lite.project.cashiersaas.dto.response.identity.MerchantOutletChoiceResponse;
import org.lite.project.cashiersaas.dto.response.identity.RegisterUserResponse;
import org.lite.project.cashiersaas.service.LoginService;
import org.lite.project.cashiersaas.service.AuthSessionService;
import org.lite.project.cashiersaas.service.RegisterService;
import org.lite.project.cashiersaas.shared.AuthenticationConstants;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;

import java.util.List;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final LoginService loginService;
    private final RegisterService registerService;
    private final AuthSessionService authSessionService;
    private final RefreshCookieService refreshCookieService;

    @PostMapping("/login")
    @SecurityRequirements
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletResponse httpResponse) {
        LoginResponse response = loginService.login(request);
        refreshCookieService.set(httpResponse, response.getRefreshToken());
        return ApiResponse.success("Login berhasil.", response);
    }

    @PostMapping("/register")
    @SecurityRequirements
    public ApiResponse<RegisterUserResponse> register(@Valid @RequestBody RegisterUserRequest request,
                                                       HttpServletResponse httpResponse) {
        RegisterUserResponse response = registerService.register(request);
        refreshCookieService.set(httpResponse, response.getRefreshToken());
        return ApiResponse.created("Registrasi akun berhasil.", response);
    }

    @PostMapping("/select-merchant")
    public ApiResponse<LoginResponse> selectMerchant(@AuthenticationPrincipal AccountPrincipal principal,
                                                      @Valid @RequestBody SelectMerchantRequest request) {
        return ApiResponse.success("Merchant dan outlet dipilih.",
                loginService.selectMerchant(principal, request.getTenantId(), request.getOutletId()));
    }

    @GetMapping("/outlets")
    public ApiResponse<List<MerchantOutletChoiceResponse>> outlets(@AuthenticationPrincipal AccountPrincipal principal) {
        return ApiResponse.success("Outlet yang dapat diakses berhasil diambil.", loginService.listChoices(principal));
    }

    @GetMapping("/csrf")
    @SecurityRequirements
    public ApiResponse<String> csrf(CsrfToken csrfToken) {
        return ApiResponse.success("CSRF token tersedia.", csrfToken.getToken());
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    public ApiResponse<LoginResponse> refresh(
            @CookieValue(value = RefreshCookieService.NAME, required = false) String refreshToken,
            HttpServletResponse httpResponse) {
        AuthSessionService.IssuedTokens tokens = authSessionService.refresh(refreshToken);
        refreshCookieService.set(httpResponse, tokens.getRefreshToken());
        LoginResponse response = new LoginResponse();
        response.setAccessToken(tokens.getAccessToken());
        response.setTokenType(AuthenticationConstants.BEARER_SCHEME);
        response.setExpiresAt(tokens.getExpiresAt());
        response.setTenantId(tokens.getTenantId());
        response.setOutletId(tokens.getOutletId());
        response.setRole(tokens.getRole());
        response.setNextStep(tokens.getNextStep());
        return ApiResponse.success("Sesi diperbarui.", response);
    }

    @PostMapping("/logout")
    @SecurityRequirements
    public ApiResponse<String> logout(
            @CookieValue(value = RefreshCookieService.NAME, required = false) String refreshToken,
            HttpServletResponse httpResponse) {
        authSessionService.logout(refreshToken);
        refreshCookieService.clear(httpResponse);
        return ApiResponse.success("Logout berhasil.", "LOGGED_OUT");
    }
}
