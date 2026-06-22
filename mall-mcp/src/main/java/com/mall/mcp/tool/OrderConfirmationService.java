package com.mall.mcp.tool;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mall.mcp.config.MallMcpProperties;
import com.mall.mcp.model.ConfirmOrderResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class OrderConfirmationService {

    private static final Duration EXPIRED_CONFIRMATION_GRACE = Duration.ofMinutes(1);

    private final MallMcpProperties properties;
    private final Clock clock;
    private final Cache<String, OrderConfirmation> confirmations;

    @Autowired
    public OrderConfirmationService(MallMcpProperties properties) {
        this(properties, Clock.systemUTC());
    }

    OrderConfirmationService(MallMcpProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.confirmations = Caffeine.newBuilder()
                .expireAfterWrite(properties.getConfirmationTtl().plus(EXPIRED_CONFIRMATION_GRACE))
                .ticker(() -> clock.millis() * 1_000_000)
                .build();
    }

    public OrderConfirmation prepare(String sessionId, ConfirmOrderResponse snapshot) {
        String confirmationId = UUID.randomUUID().toString();
        OrderConfirmation confirmation = new OrderConfirmation(
                confirmationId,
                sessionId,
                snapshot,
                Instant.now(clock).plus(properties.getConfirmationTtl())
        );
        confirmations.put(confirmationId, confirmation);
        return confirmation;
    }

    public Optional<OrderConfirmation> find(String sessionId, String confirmationId) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(confirmationId)) {
            return Optional.empty();
        }
        OrderConfirmation confirmation = confirmations.getIfPresent(confirmationId.trim());
        if (confirmation == null || !sessionId.trim().equals(confirmation.sessionId())) {
            return Optional.empty();
        }
        return Optional.of(confirmation);
    }

    public boolean isExpired(OrderConfirmation confirmation) {
        return confirmation == null || !confirmation.expiresAt().isAfter(Instant.now(clock));
    }

    public void remove(String confirmationId) {
        if (StringUtils.hasText(confirmationId)) {
            confirmations.invalidate(confirmationId.trim());
        }
    }
}
