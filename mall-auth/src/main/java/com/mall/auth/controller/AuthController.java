package com.mall.auth.controller;

import com.mall.auth.pojo.dto.LoginRequest;
import com.mall.auth.pojo.vo.LoginResponse;
import com.mall.auth.pojo.vo.TokenPayload;
import com.mall.auth.service.SimpleTokenService;
import com.mall.common.api.ApiResponse;
import com.mall.common.context.UserContext;
import com.mall.common.context.UserInfo;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SimpleTokenService tokenService;

    public AuthController(SimpleTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        Long userId = Math.abs((long) request.username().hashCode());
        String token = tokenService.create(userId, request.username());
        return ApiResponse.success(new LoginResponse(userId, request.username(), token));
    }

    @GetMapping("/me")
    public ApiResponse<UserInfo> me() {
        return ApiResponse.success(UserContext.current().orElse(new UserInfo(1L, "demo")));
    }

    @GetMapping("/verify")
    public ApiResponse<TokenPayload> verify(@RequestHeader("Authorization") String authorization) {
        String token = authorization.replace("Bearer ", "");
        return ApiResponse.success(tokenService.verify(token));
    }
}
