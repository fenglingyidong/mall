package com.mall.auth.controller;

import com.mall.auth.pojo.dto.LoginRequest;
import com.mall.auth.pojo.entity.AuthUser;
import com.mall.auth.pojo.vo.LoginResponse;
import com.mall.auth.pojo.vo.TokenPayload;
import com.mall.auth.repository.AuthUserRepository;
import com.mall.auth.service.SimpleTokenService;
import com.mall.common.api.ApiResponse;
import com.mall.common.context.UserContext;
import com.mall.common.context.UserInfo;
import com.mall.common.exception.BusinessException;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private SimpleTokenService tokenService;

    @Autowired
    private AuthUserRepository userRepository;

    public AuthController() {
    }

    public AuthController(SimpleTokenService tokenService, AuthUserRepository userRepository) {
        this.tokenService = tokenService;
        this.userRepository = userRepository;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthUser user = userRepository.findByUsername(request.username().trim())
                .orElseThrow(() -> new BusinessException(401, "Invalid username or password"));
        if (!passwordMatches(request.password(), user.password())) {
            throw new BusinessException(401, "Invalid username or password");
        }
        String token = tokenService.create(user.id(), user.username());
        return ApiResponse.success(new LoginResponse(user.id(), user.username(), token));
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

    private boolean passwordMatches(String rawPassword, String storedPassword) {
        return StringUtils.hasText(rawPassword)
                && StringUtils.hasText(storedPassword)
                && rawPassword.equals(storedPassword);
    }
}
