package com.mall.mcp.context;

import java.time.Instant;

public record MallUserContext(
        String sessionId,
        String userId,
        String mallToken,
        String mallUsername,
        String mallPassword,
        Instant expiresAt
) {

    public MallUserContext withToken(String token, Instant newExpiresAt) {
        return new MallUserContext(sessionId, userId, token, mallUsername, mallPassword, newExpiresAt);
    }
}
