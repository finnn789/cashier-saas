package org.lite.project.cashiersaas.controller;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.dto.request.identity.LoginRequest;
import org.lite.project.cashiersaas.dto.request.identity.RegisterUserRequest;
import org.lite.project.cashiersaas.dto.response.identity.LoginResponse;
import org.lite.project.cashiersaas.dto.response.identity.RegisterUserResponse;
import org.lite.project.cashiersaas.service.LoginService;
import org.lite.project.cashiersaas.service.RegisterService;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final LoginService loginService;
    private final RegisterService registerService;

    @PostMapping("/login")
    @SecurityRequirements
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = loginService.login(request);
        return ApiResponse.success("Login berhasil.", response);
    }

    @PostMapping("/register")
    @SecurityRequirements
    public ApiResponse<RegisterUserResponse> register(@Valid @RequestBody RegisterUserRequest request) {
        return ApiResponse.created("Registrasi akun berhasil.", registerService.register(request));
    }
}
