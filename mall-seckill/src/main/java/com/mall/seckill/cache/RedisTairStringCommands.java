package com.mall.seckill.cache;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RedisTairStringCommands implements TairStringCommands {

    private static final String VERSION_SEPARATOR = "|";
    private static final DefaultRedisScript<String> GET_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('EXGET', KEYS[1])
            if not current then
                return nil
            end
            return tostring(current[1]) .. '%s' .. tostring(current[2])
            """.formatted(VERSION_SEPARATOR), String.class);
    private static final DefaultRedisScript<Long> SET_IF_NEWER_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('EXGET', KEYS[1])
            if current and current[2] and tonumber(current[2]) >= tonumber(ARGV[2]) then
                return 0
            end
            redis.call('EXSET', KEYS[1], ARGV[1], 'ABS', ARGV[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisTairStringCommands(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public VersionedCacheValue get(String key) {
        return parseVersionedValue(redisTemplate.execute(GET_SCRIPT, List.of(key)));
    }

    @Override
    public void set(String key, String value, long version) {
        redisTemplate.execute(SET_IF_NEWER_SCRIPT, List.of(key), value, String.valueOf(version));
    }

    private VersionedCacheValue parseVersionedValue(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        int separatorIndex = response.lastIndexOf(VERSION_SEPARATOR);
        if (separatorIndex < 0) {
            return new VersionedCacheValue(response, null);
        }
        String value = response.substring(0, separatorIndex);
        Long version = parseLong(response.substring(separatorIndex + VERSION_SEPARATOR.length()));
        return new VersionedCacheValue(value, version);
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Long.parseLong(value);
    }
}
