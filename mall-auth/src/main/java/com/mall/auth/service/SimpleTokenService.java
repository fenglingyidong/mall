package com.mall.auth.service;

import com.mall.auth.pojo.vo.TokenPayload;

public interface SimpleTokenService {

    String create(Long userId, String username);

    TokenPayload verify(String token);
}
