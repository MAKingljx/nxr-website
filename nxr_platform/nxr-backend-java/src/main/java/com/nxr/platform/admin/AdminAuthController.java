package com.nxr.platform.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    public AdminAuthController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @PostMapping("/login")
    public AdminAuthService.LoginResponse login(@Valid @RequestBody LoginPayload payload) {
        return adminAuthService.login(new AdminAuthService.LoginRequest(payload.username(), payload.password()));
    }

    public record LoginPayload(
        @NotBlank String username,
        @NotBlank String password
    ) {
    }
}
