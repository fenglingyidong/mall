package com.mall.message;

import java.time.Instant;

public record MessageRecord(ReliableMessage message, MessageStatus status, Instant updatedAt, String error) {
}
