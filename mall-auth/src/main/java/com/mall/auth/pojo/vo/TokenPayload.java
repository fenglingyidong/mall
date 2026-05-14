package com.mall.auth.pojo.vo;

public record TokenPayload(Long userId, String username, long expireAt) {
}


