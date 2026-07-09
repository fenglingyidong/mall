package com.mall.message;

import java.time.Instant;
import java.util.UUID;

public record ReliableMessage(
        String messageId,
        String exchange,
        String routingKey,
        String businessKey,
        String payload,
        Long bucketShardKey,
        Long delayMillis,
        Instant createdAt
) {

    public static ReliableMessage of(String exchange, String routingKey, String businessKey, String payload) {
        return of(exchange, routingKey, businessKey, payload, null);
    }

    public static ReliableMessage of(String exchange, String routingKey, String businessKey, String payload, Long delayMillis) {
        return of(exchange, routingKey, businessKey, payload, null, delayMillis);
    }

    public static ReliableMessage of(String exchange,
                                     String routingKey,
                                     String businessKey,
                                     String payload,
                                     Long bucketShardKey,
                                     Long delayMillis) {
        return new ReliableMessage(UUID.randomUUID().toString(), exchange, routingKey, businessKey, payload,
                bucketShardKey, delayMillis, Instant.now());
    }
}
