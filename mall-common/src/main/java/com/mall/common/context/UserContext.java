package com.mall.common.context;

import com.alibaba.ttl.TransmittableThreadLocal;

import java.util.Optional;

public final class UserContext {

    private static final TransmittableThreadLocal<UserInfo> HOLDER = new TransmittableThreadLocal<>();

    private UserContext() {
    }

    public static void set(UserInfo userInfo) {
        HOLDER.set(userInfo);
    }

    public static Optional<UserInfo> current() {
        return Optional.ofNullable(HOLDER.get());
    }

    public static Long currentUserIdOrDefault(Long defaultUserId) {
        return current().map(UserInfo::userId).orElse(defaultUserId);
    }

    public static UserInfo capture() {
        return HOLDER.get();
    }

    public static void restore(UserInfo userInfo) {
        if (userInfo == null) {
            HOLDER.remove();
            return;
        }
        HOLDER.set(userInfo);
    }

    public static void clear() {
        HOLDER.remove();
    }
}


