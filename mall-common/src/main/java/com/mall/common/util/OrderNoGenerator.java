package com.mall.common.util;

import java.util.UUID;

public final class OrderNoGenerator {

    private OrderNoGenerator() {
    }

    public static String next(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
