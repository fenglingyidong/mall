package com.mall.mcp.tool;

import com.mall.mcp.model.ConfirmOrderResponse;

import java.time.Instant;

record OrderConfirmation(
        String confirmationId,
        String sessionId,
        ConfirmOrderResponse snapshot,
        Instant expiresAt
) {
}
