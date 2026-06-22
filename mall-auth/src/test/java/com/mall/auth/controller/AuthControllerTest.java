package com.mall.auth.controller;

import com.mall.auth.pojo.dto.LoginRequest;
import com.mall.auth.pojo.entity.AuthUser;
import com.mall.auth.repository.AuthUserRepository;
import com.mall.auth.service.SimpleTokenService;
import com.mall.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    @Test
    void loginShouldValidateDatabaseUserPassword() {
        AuthUserRepository userRepository = mock(AuthUserRepository.class);
        SimpleTokenService tokenService = mock(SimpleTokenService.class);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(new AuthUser(10001L, "alice", "demo123")));
        when(tokenService.create(10001L, "alice")).thenReturn("token-from-auth");
        AuthController controller = new AuthController(tokenService, userRepository);

        var response = controller.login(new LoginRequest("alice", "demo123"));

        assertEquals(0, response.code());
        assertEquals(10001L, response.data().userId());
        assertEquals("alice", response.data().username());
        assertEquals("token-from-auth", response.data().token());
    }

    @Test
    void loginShouldRejectWrongPassword() {
        AuthUserRepository userRepository = mock(AuthUserRepository.class);
        SimpleTokenService tokenService = mock(SimpleTokenService.class);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(new AuthUser(10001L, "alice", "demo123")));
        AuthController controller = new AuthController(tokenService, userRepository);

        assertThrows(BusinessException.class, () -> controller.login(new LoginRequest("alice", "wrong")));
    }
}
